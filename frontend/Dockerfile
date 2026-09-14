# Bases fijadas por digest; actualizar deliberadamente y repetir las pruebas.
FROM node:22-alpine@sha256:c610fcdfb1d5b4740dd70c284ed3cb16bb857e0f7166196e36a5501df7a3aa32 AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY index.html vite.config.js .oxlintrc.json ./
COPY src ./src
COPY public ./public
COPY tests ./tests
COPY tools/validate-docker-build.mjs ./tools/validate-docker-build.mjs

# Solo configuración PÚBLICA incorporada al JavaScript. Nunca secretos en ARG/VITE_*.
ARG VITE_API_BASE_URL
ARG VITE_ENTRA_CLIENT_ID
ARG VITE_ENTRA_TENANT_ID
ARG VITE_ENTRA_REDIRECT_URI
ARG VITE_ENTRA_API_SCOPE
RUN node tools/validate-docker-build.mjs \
  && npm run lint -- src tests tools vite.config.js \
  && npm test -- --test-reporter=dot \
  && npm run build

FROM nginxinc/nginx-unprivileged:stable-alpine@sha256:442753882674b49ae2c1de83ed67896131c0777f56df5005e356e62bc3f7e7ce AS runtime
COPY docker/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
USER 101:101
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
# No hay plantillas ni mutaciones de configuración al arrancar.
ENTRYPOINT ["nginx"]
CMD ["-g", "daemon off;"]
