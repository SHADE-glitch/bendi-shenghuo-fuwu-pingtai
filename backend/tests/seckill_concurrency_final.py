#!/usr/bin/env python3
"""最终回归：秒杀并发测试（100 请求 / 10 并发 / 100 用户，验证防超卖与一人一单）"""
import json, subprocess, sys, time, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
from collections import Counter

BASE = "http://localhost:8081"
REDIS = ["redis-cli", "-h", "127.0.0.1", "-p", "6379"]
MYSQL = ["mysql", "-h127.0.0.1", "-P3306", "-uroot", "-prootpassword", "-N", "hmdp"]
VID = 30003
STOCK = 50
CODE = "123456"

def http(method, path, body=None, token=None):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("authorization", token)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except Exception:
            return e.code, {}
    except Exception as e:
        return -1, {"error": str(e)}

def redis_set(k, v):
    subprocess.run(REDIS + ["SET", k, v], capture_output=True)
def redis_del(k):
    subprocess.run(REDIS + ["DEL", k], capture_output=True)
def mysql(sql):
    r = subprocess.run(MYSQL + ["-e", sql], capture_output=True, text=True)
    return r.stdout.strip()

# 1. 准备测试券
# 注意：tb_voucher_order 也必须一并清理。下方手机号列表是硬编码固定值，
# 重跑时同一批用户会命中「一人一单」唯一键（errorCode 30002）而非库存不足（30001），
# 且历史残留订单会被计入「DB 订单数」从而误报超卖。
mysql(f"DELETE FROM tb_voucher_order WHERE voucher_id={VID};")
mysql(f"DELETE FROM tb_seckill_voucher WHERE voucher_id={VID};")
mysql(f"DELETE FROM tb_voucher WHERE id={VID};")
mysql(f"INSERT INTO tb_voucher (id,shop_id,title,sub_title,rules,pay_value,actual_value,type,status) VALUES ({VID},1,'最终并发测试券','t','r',100,1000,1,1);")
mysql(f"INSERT INTO tb_seckill_voucher (voucher_id,stock,begin_time,end_time) VALUES ({VID},{STOCK},'2026-01-01 00:00:00','2027-12-31 00:00:00');")
redis_set(f"seckill:stock:{VID}", str(STOCK))
redis_del(f"seckill:orders:{VID}")
print(f"[setup] 券 {VID} stock={STOCK}, 100 用户, 10 并发")

# 2. 登录 100 用户
phones = [f"1393{i:07d}" for i in range(1000000, 1000100)]
tokens = []
def login(p):
    redis_set(f"login:code:{p}", CODE)
    st, r = http("POST", "/users/login", {"phone": p, "code": CODE})
    if st == 200 and r.get("success"):
        return r["data"]["token"]
    return None
with ThreadPoolExecutor(max_workers=10) as ex:
    for f in ex.map(login, phones):
        if f:
            tokens.append(f)
print(f"[setup] 成功登录 {len(tokens)} 用户")

# 3. 并发秒杀
def seckill(t):
    t0 = time.time()
    st, r = http("POST", f"/voucher-orders/seckill-orders?voucherId={VID}", token=t)
    return (time.time()-t0)*1000, r.get("success"), r.get("errorCode"), (r.get("data") or {}).get("orderId")

results = []
with ThreadPoolExecutor(max_workers=10) as ex:
    for f in ex.map(seckill, tokens):
        results.append(f)

lat = sorted(r[0] for r in results)
succ = [r for r in results if r[1]]
fail = [r for r in results if not r[1]]
total_t = sum(r[0] for r in results)/len(results)
def pct(p):
    k = (len(lat)-1)*p; f=int(k); c=min(f+1,len(lat)-1)
    return lat[f]+(lat[c]-lat[f])*(k-f)

print("\n===== 秒杀并发结果 =====")
print(f"总请求: {len(results)}  成功: {len(succ)}  失败: {len(fail)}")
print(f"失败原因分布: {dict(Counter(r[2] for r in fail))}")
print(f"平均: {total_t:.1f}ms  P50: {pct(0.5):.1f}ms  P95: {pct(0.95):.1f}ms  P99: {pct(0.99):.1f}ms")
print(f"错误率: {len(fail)/len(results)*100:.2f}%")

# 4. 校验
time.sleep(2)
db_stock = mysql(f"SELECT stock FROM tb_seckill_voucher WHERE voucher_id={VID};")
r_stock = subprocess.run(REDIS + ["GET", f"seckill:stock:{VID}"], capture_output=True, text=True).stdout.strip()
orders = mysql(f"SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id={VID} AND is_deleted=0;")
dup = mysql(f"SELECT COUNT(*) FROM (SELECT user_id FROM tb_voucher_order WHERE voucher_id={VID} AND is_deleted=0 GROUP BY user_id HAVING COUNT(*)>1) t;")
print("\n===== 数据校验 =====")
print(f"DB 剩余库存: {db_stock} (初始 {STOCK})")
print(f"Redis 剩余库存: {r_stock}")
print(f"DB 订单数: {orders}")
print(f"重复下单用户数: {dup}")
print(f"是否超卖: {'是(异常)' if int(orders or 0) > STOCK else '否'}")
print(f"Redis/DB 库存一致: {'是' if db_stock == r_stock else '否'}")
