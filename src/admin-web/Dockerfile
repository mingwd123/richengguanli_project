FROM node:22-alpine AS build
WORKDIR /workspace/src/admin-web
COPY src/admin-web/package*.json ./
RUN npm ci
COPY src/admin-web/ ./
COPY src/web-user/image /workspace/src/web-user/image
ARG VITE_API_BASE_URL=/api/v1
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

FROM nginx:1.27-alpine
COPY deploy/spa-nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/src/admin-web/dist /usr/share/nginx/html
EXPOSE 80
