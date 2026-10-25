# Build from the repository root so file: SDK dependencies stay reproducible.
FROM node:24-alpine AS dependencies
RUN apk add --no-cache bash
WORKDIR /app
COPY vendor/uni-form/ vendor/uni-form/
COPY scripts/build-uniform-frontend.sh scripts/build-uniform-frontend.sh
RUN npm install --global "$(node -p "require('./vendor/uni-form/package.json').packageManager")" \
    && bash scripts/build-uniform-frontend.sh
WORKDIR /app/bpm-frontend
COPY bpm-frontend/package.json bpm-frontend/package-lock.json ./
RUN npm ci
COPY bpm-frontend/ ./

FROM dependencies AS dev
EXPOSE 3000
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]

FROM dependencies AS build
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/bpm-frontend/dist /usr/share/nginx/html
EXPOSE 80
