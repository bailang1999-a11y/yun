FROM node:22-bookworm-slim AS build
WORKDIR /workspace/apps/h5
COPY apps/h5/package.json apps/h5/package-lock.json ./
RUN npm ci
COPY apps/h5 ./
ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

FROM nginx:1.27-alpine
COPY apps/h5/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/apps/h5/dist /usr/share/nginx/html
EXPOSE 80
