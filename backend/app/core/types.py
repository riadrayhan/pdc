"""Custom SQLAlchemy types for cross-database compatibility"""
import uuid
import json
from sqlalchemy.types import TypeDecorator, CHAR, Text

try:
    from sqlalchemy.dialects.postgresql import UUID as PG_UUID, JSONB as PG_JSONB
except ImportError:
    PG_UUID = None
    PG_JSONB = None

try:
    from sqlalchemy.dialects.mysql import JSON as MYSQL_JSON
except ImportError:
    MYSQL_JSON = None


class UUID(TypeDecorator):
    """Platform-independent UUID type.

    Uses PostgreSQL's UUID type, MySQL CHAR(36), or SQLite CHAR(36).
    """
    impl = CHAR
    cache_ok = True

    def load_dialect_impl(self, dialect):
        if dialect.name == 'postgresql' and PG_UUID is not None:
            return dialect.type_descriptor(PG_UUID(as_uuid=True))
        else:
            # MySQL and SQLite both use CHAR(36)
            return dialect.type_descriptor(CHAR(36))

    def process_bind_param(self, value, dialect):
        if value is None:
            return value
        elif dialect.name == 'postgresql':
            return value
        else:
            if isinstance(value, uuid.UUID):
                return str(value)
            return value

    def process_result_value(self, value, dialect):
        if value is None:
            return value
        else:
            if isinstance(value, uuid.UUID):
                return value
            return uuid.UUID(value)


class JSONB(TypeDecorator):
    """Platform-independent JSONB type.

    Uses PostgreSQL's JSONB, MySQL's native JSON, or SQLite Text.
    """
    impl = Text
    cache_ok = True

    def load_dialect_impl(self, dialect):
        if dialect.name == 'postgresql' and PG_JSONB is not None:
            return dialect.type_descriptor(PG_JSONB())
        elif dialect.name == 'mysql' and MYSQL_JSON is not None:
            return dialect.type_descriptor(MYSQL_JSON())
        else:
            return dialect.type_descriptor(Text())

    def process_bind_param(self, value, dialect):
        if value is None:
            return value
        if dialect.name in ('postgresql', 'mysql'):
            return value
        else:
            return json.dumps(value)

    def process_result_value(self, value, dialect):
        if value is None:
            return value
        if dialect.name in ('postgresql', 'mysql'):
            if isinstance(value, str):
                return json.loads(value)
            return value
        else:
            return json.loads(value)
