# Stage 1: Build React/Vite
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .

# Add environment variable for API base URL 
ARG VITE_TELEGRAM_BOT
ENV VITE_TELEGRAM_BOT=${VITE_TELEGRAM_BOT}
ENV VITE_API_BASE_URL=/api/v1
RUN npm run build

# Stage 2: Setup Nginx
FROM nginx:alpine
LABEL org.opencontainers.image.source="https://github.com/DuckTonn/UniHub"
# Copy Nginx configuration file into container
ENV UNIHUB_TRUSTED_TLS_PROXY=unix:
ENV NGINX_ENVSUBST_FILTER=^UNIHUB_TRUSTED_TLS_PROXY$
COPY nginx.conf /etc/nginx/templates/default.conf.template
# Copy built frontend files from the build stage to Nginx's html directory
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
