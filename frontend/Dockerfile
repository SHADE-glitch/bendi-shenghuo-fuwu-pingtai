# 开发模式 - 前端 Dockerfile
FROM node:22-alpine

WORKDIR /app

# 先复制依赖清单并安装，利用 Docker 层缓存
COPY package.json package-lock.json ./
RUN npm ci

# 复制源码（运行时会被 docker-compose 的卷挂载覆盖，支持热更新）
COPY . .

EXPOSE 5173

CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]