FROM python:3.12-alpine

RUN addgroup -S diagnostics && adduser -S -G diagnostics diagnostics
WORKDIR /app
COPY receiver.py schema.py /app/
RUN mkdir /data && chown diagnostics:diagnostics /data
USER diagnostics
EXPOSE 8091
ENTRYPOINT ["python", "/app/receiver.py"]
