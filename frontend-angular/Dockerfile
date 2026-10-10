FROM node:22-alpine@sha256:c610fcdfb1d5b4740dd70c284ed3cb16bb857e0f7166196e36a5501df7a3aa32 AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY angular.json tsconfig*.json ./
COPY src ./src
COPY tools ./tools
# Public browser configuration only. No client secret or private identity material.
ARG P360_API_BASE_URL
ARG P360_ENTRA_CLIENT_ID
ARG P360_ENTRA_TENANT_ID
ARG P360_ENTRA_REDIRECT_URI
ARG P360_ENTRA_API_SCOPE
ARG P360_CHECKOUT_MODE=HTTP
RUN npm run configure && npm run lint && npm test && npm run build

FROM nginxinc/nginx-unprivileged:stable-alpine@sha256:442753882674b49ae2c1de83ed67896131c0777f56df5005e356e62bc3f7e7ce
COPY docker/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/pedidos360/browser /usr/share/nginx/html
USER 101:101
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["nginx"]
CMD ["-g", "daemon off;"]
