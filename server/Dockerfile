# Python 标准库服务；镜像仅包含程序和合成默认配置，不含部署配置或来源。
ARG BASE_IMAGE=python:3.11-slim
FROM ${BASE_IMAGE}
WORKDIR /app
COPY app/ ./app/
COPY tools/init_config.py ./tools/init_config.py
COPY config/settings.example.json ./config/settings.json
RUN mkdir -p data/seed
ENV PYTHONUNBUFFERED=1 \
    TZ=UTC
EXPOSE 8420
CMD ["python3", "app/main.py"]
