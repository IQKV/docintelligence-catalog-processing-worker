-- Initialize Catalog Processing Worker Database
-- This script runs when the PostgreSQL container starts for the first time

-- Create additional schemas
CREATE SCHEMA IF NOT EXISTS catalogservice;

-- Set default search path
ALTER DATABASE catalogservice SET search_path TO catalogservice, public;

-- Create extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_stat_statements";

-- Grant permissions
GRANT ALL PRIVILEGES ON DATABASE catalogservice TO svc_catalog_dba;
GRANT ALL PRIVILEGES ON SCHEMA public TO svc_catalog_dba;
GRANT ALL PRIVILEGES ON SCHEMA catalogservice TO svc_catalog_dba;

SELECT 'Catalog Processing Worker Database initialized successfully' AS status;
