from __future__ import annotations

import redis

from .settings import Settings


def build_redis_client(settings: Settings):
    try:
        client = redis.Redis(
            host=settings.redis_host,
            port=settings.redis_port,
            db=settings.redis_db,
            decode_responses=True,
            socket_timeout=1.0,
        )
        client.ping()
        return client
    except Exception:
        return None
