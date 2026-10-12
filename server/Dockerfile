FROM node:24-bookworm-slim
WORKDIR /app
COPY --chown=node:node package.json src.js ./
COPY --chown=node:node public ./public
RUN mkdir /data && chown node:node /data
USER node
ENV PORT=3000 DB_PATH=/data/calendar.sqlite
EXPOSE 3000
CMD ["node", "src.js"]
