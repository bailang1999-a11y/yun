FROM node:22-bookworm-slim AS build
WORKDIR /workspace/apps/admin
ARG NPM_REGISTRY=https://registry.npmjs.org/
COPY apps/admin/package.json apps/admin/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
  npm config set registry "$NPM_REGISTRY" \
  && npm config set strict-ssl true \
  && npm config set fetch-retries 5 \
  && npm config set fetch-retry-mintimeout 20000 \
  && npm config set fetch-retry-maxtimeout 120000 \
  && npm ci
COPY apps/admin ./
ARG VITE_ADMIN_API_BASE_URL=/api
ENV VITE_ADMIN_API_BASE_URL=$VITE_ADMIN_API_BASE_URL
RUN npm run build

FROM nginx:1.27-alpine
COPY apps/admin/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/apps/admin/dist /usr/share/nginx/html
EXPOSE 80
