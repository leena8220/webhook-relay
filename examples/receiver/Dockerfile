FROM python:3.13-alpine

RUN addgroup -S receiver && adduser -S -D -H -u 10001 -G receiver receiver
WORKDIR /app
COPY --chown=receiver:receiver server.py /app/server.py

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["python", "/app/server.py"]
