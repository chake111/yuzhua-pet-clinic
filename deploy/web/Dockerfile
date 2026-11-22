FROM node:22-alpine AS build
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
ARG VITE_DEMO_PASSWORD=""
ENV VITE_DEMO_PASSWORD=$VITE_DEMO_PASSWORD
RUN npm run build
# 只发布落地页和素材；说明与核对截图不进镜像，且在构建期统一预生成 .gz
COPY deploy/demo/index.html /src/demo-stage/index.html
COPY deploy/demo/agent-assets/ /src/demo-stage/agent-assets/
RUN node tools/postbuild-assets.mjs --gzip-only /src/demo-stage

FROM nginx:1.28-alpine
COPY deploy/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
COPY --from=build /src/demo-stage/ /usr/share/nginx/html/demo/
