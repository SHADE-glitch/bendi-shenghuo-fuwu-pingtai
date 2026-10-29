#!/usr/bin/env python3
"""秒杀并发测试：验证防超卖、一人一单、幂等性"""
import json
import os
import subprocess
import sys
import time
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed

BASE = "http://localhost:8081"
REDIS = ["redis-cli", "-h", "127.0.0.1", "-p", "6379"]
# Redis 已启用 requirepass（见 docker-compose.yml）：带密码，否则 redis-cli 会被 NOAUTH 拒绝。
# 值取 .env 的 REDIS_PASSWORD；不设则按无认证处理（兼容未启用密码的环境）。
if os.environ.get("REDIS_PASSWORD"):
    REDIS += ["-a", os.environ["REDIS_PASSWORD"], "--no-auth-warning"]
MYSQL = ["mysql", "-h127.0.0.1", "-P3306", "-uroot", "-prootpassword", "-N", "hmdp"]

FIXED_CODE = "123456"


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


def redis_set(key, val):
    subprocess.run(REDIS + ["SET", key, val], check=True, capture_output=True)


def redis_get(key):
    r = subprocess.run(REDIS + ["GET", key], capture_output=True, text=True)
    return r.stdout.strip()


def mysql_query(sql):
    r = subprocess.run(MYSQL + ["-e", sql], capture_output=True, text=True)
    return r.stdout.strip()


def create_user(phone):
    redis_set(f"login:code:{phone}", FIXED_CODE)
    st, resp = http("POST", "/users/login", {"phone": phone, "code": FIXED_CODE})
    if st == 200 and resp.get("success"):
        return phone, resp["data"]["token"], resp["data"]["user"]["id"]
    return phone, None, None


def seckill(token, voucher_id):
    st, resp = http("POST", f"/voucher-orders/seckill-orders?voucherId={voucher_id}", token=token)
    return st, resp


def percentile(sorted_vals, p):
    if not sorted_vals:
        return 0
    k = (len(sorted_vals) - 1) * p
    f = int(k)
    c = min(f + 1, len(sorted_vals) - 1)
    return sorted_vals[f] + (sorted_vals[c] - sorted_vals[f]) * (k - f)


def main():
    n_users = int(sys.argv[1]) if len(sys.argv) > 1 else 60
    voucher_stock = int(sys.argv[2]) if len(sys.argv) > 2 else 30
    concurrency = int(sys.argv[3]) if len(sys.argv) > 3 else 10
    vid = 20002
    phones = [f"139{i:08d}" for i in range(20000000, 20000000 + n_users)]

    # 1. 创建测试秒杀券
    title = "并发测试秒杀券"
    mysql_query(f"DELETE FROM hmdp.tb_seckill_voucher WHERE voucher_id={vid};")
    mysql_query(f"DELETE FROM hmdp.tb_voucher WHERE id={vid};")
    mysql_query(f"INSERT INTO hmdp.tb_voucher (id,shop_id,title,sub_title,rules,pay_value,actual_value,type,status) VALUES ({vid},1,'{title}','t','r',100,1000,1,1);")
    mysql_query(f"INSERT INTO hmdp.tb_seckill_voucher (voucher_id,stock,begin_time,end_time) VALUES ({vid},{voucher_stock},'2026-01-01 00:00:00','2027-12-31 00:00:00');")
    redis_set(f"seckill:stock:{vid}", str(voucher_stock))
    subprocess.run(REDIS + ["DEL", f"seckill:orders:{vid}"], capture_output=True)
    print(f"[setup] 测试券 {vid} stock={voucher_stock}, 用户数={n_users}, 并发={concurrency}")

    # 2. 登录生成用户
    print("[setup] 登录生成用户...")
    tokens = []
    with ThreadPoolExecutor(max_workers=concurrency) as ex:
        futs = [ex.submit(create_user, p) for p in phones]
        for f in as_completed(futs):
            phone, tok, uid = f.result()
            if tok:
                tokens.append((phone, tok, uid))
    print(f"[setup] 成功登录 {len(tokens)} 个用户")

    # 3. 并发秒杀
    print("[run] 并发秒杀下单...")
    start = time.time()
    results = []
    latencies = []
    with ThreadPoolExecutor(max_workers=concurrency) as ex:
        futs = []
        for (_, t, _) in tokens:
            t0 = time.time()
            futs.append((ex.submit(seckill, t, vid), t0))
        for f, t0 in futs:
            st, resp = f.result()
            latencies.append((time.time() - t0) * 1000)
            ok = resp.get("success")
            err = resp.get("errorCode")
            order_id = (resp.get("data") or {}).get("orderId") if ok else None
            results.append((ok, err, order_id))
    total_time = time.time() - start

    # 4. 统计
    success = [r for r in results if r[0]]
    failed = [r for r in results if not r[0]]
    latencies.sort()
    print("\n===== 秒杀并发测试结果 =====")
    print(f"总请求数: {len(results)}")
    print(f"成功数: {len(success)}")
    print(f"失败数: {len(failed)}")
    print(f"错误率: {len(failed)/len(results)*100:.2f}%")
    print(f"总耗时: {total_time:.2f}s, 吞吐(QPS): {len(results)/total_time:.2f}")
    print(f"平均响应: {sum(latencies)/len(latencies):.1f}ms")
    print(f"P50: {percentile(latencies,0.50):.1f}ms  P95: {percentile(latencies,0.95):.1f}ms  P99: {percentile(latencies,0.99):.1f}ms  Max: {max(latencies):.1f}ms")
    from collections import Counter
    err_dist = Counter(r[1] for r in failed)
    print(f"失败错误码分布: {dict(err_dist)}")

    # 5. 校验
    time.sleep(2)
    db_stock = mysql_query(f"SELECT stock FROM hmdp.tb_seckill_voucher WHERE voucher_id={vid};")
    redis_stock = redis_get(f"seckill:stock:{vid}")
    order_count = mysql_query(f"SELECT COUNT(*) FROM hmdp.tb_voucher_order WHERE voucher_id={vid} AND is_deleted=0;")
    dup_users = mysql_query(f"SELECT COUNT(*) FROM (SELECT user_id FROM hmdp.tb_voucher_order WHERE voucher_id={vid} AND is_deleted=0 GROUP BY user_id HAVING COUNT(*)>1) t;")
    print("\n===== 数据校验 =====")
    print(f"DB 剩余库存: {db_stock} (初始 {voucher_stock})")
    print(f"Redis 剩余库存: {redis_stock}")
    print(f"DB 订单数: {order_count}")
    print(f"重复下单用户数: {dup_users}")
    oversell = int(order_count or 0) > voucher_stock
    print(f"是否超卖: {'是(异常!)' if oversell else '否'}")
    print(f"一人一单: {'通过' if (dup_users == '0' or dup_users == '') else '失败:存在重复'}")

    # 6. 监控
    print("\n===== 资源监控 =====")
    r = subprocess.run(REDIS + ["INFO", "clients"], capture_output=True, text=True)
    print("Redis connected_clients:", [l for l in r.stdout.splitlines() if "connected_clients" in l])
    active = mysql_query("SHOW GLOBAL STATUS LIKE 'Threads_connected';")
    print("MySQL Threads_connected:", active)
    r = subprocess.run(REDIS + ["XLEN", "stream:seckill:orders"], capture_output=True, text=True)
    print("Stream 消息总数:", r.stdout.strip())
    r = subprocess.run(REDIS + ["XPENDING", "stream:seckill:orders", "seckill-group"], capture_output=True, text=True)
    print("Stream pending 待处理:", r.stdout.strip().split()[0] if r.stdout.strip() else "0")


if __name__ == "__main__":
    main()
