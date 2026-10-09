#!/bin/bash
# PostgreSQL database initialization script
# Creates databases and roles for each service
# Run as root postgres user during container initialization

set -e

# Wait for postgres to be ready (default database is 'postgres' matching POSTGRES_USER)
until pg_isready -U postgres -d postgres; do
  sleep 1
done

# Create databases
psql -v ON_ERROR_STOP=1 -U postgres -d postgres <<-EOSQL
    CREATE DATABASE "user";
    CREATE DATABASE location;
    CREATE DATABASE dispatch;
    CREATE DATABASE payment;
EOSQL

# Create roles with passwords from environment variables (or update if exists)
# Use psql variables to avoid quoting issues in heredoc
psql -v ON_ERROR_STOP=1 -U postgres -d postgres \
    -v user_pwd="${USER_DB_PASSWORD}" \
    -v location_pwd="${LOCATION_DB_PASSWORD}" \
    -v dispatch_pwd="${DISPATCH_DB_PASSWORD}" \
    -v payment_pwd="${PAYMENT_DB_PASSWORD}" \
    <<-EOSQL
    CREATE ROLE IF NOT EXISTS user_app WITH LOGIN PASSWORD :'user_pwd';
    ALTER ROLE user_app WITH PASSWORD :'user_pwd';
    CREATE ROLE IF NOT EXISTS location_app WITH LOGIN PASSWORD :'location_pwd';
    ALTER ROLE location_app WITH PASSWORD :'location_pwd';
    CREATE ROLE IF NOT EXISTS dispatch_app WITH LOGIN PASSWORD :'dispatch_pwd';
    ALTER ROLE dispatch_app WITH PASSWORD :'dispatch_pwd';
    CREATE ROLE IF NOT EXISTS payment_app WITH LOGIN PASSWORD :'payment_pwd';
    ALTER ROLE payment_app WITH PASSWORD :'payment_pwd';
EOSQL

# Grant privileges
psql -v ON_ERROR_STOP=1 -U postgres -d postgres <<-EOSQL
    GRANT ALL PRIVILEGES ON DATABASE "user" TO user_app;
    GRANT ALL PRIVILEGES ON DATABASE location TO location_app;
    GRANT ALL PRIVILEGES ON DATABASE dispatch TO dispatch_app;
    GRANT ALL PRIVILEGES ON DATABASE payment TO payment_app;
EOSQL

# Connect to each database and grant schema privileges
psql -v ON_ERROR_STOP=1 -U postgres -d "user" <<-EOSQL
    GRANT ALL ON SCHEMA public TO user_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO user_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO user_app;
EOSQL

psql -v ON_ERROR_STOP=1 -U postgres -d location <<-EOSQL
    GRANT ALL ON SCHEMA public TO location_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO location_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO location_app;
    -- Enable PostGIS extension for spatial data
    CREATE EXTENSION IF NOT EXISTS postgis;
EOSQL

psql -v ON_ERROR_STOP=1 -U postgres -d dispatch <<-EOSQL
    GRANT ALL ON SCHEMA public TO dispatch_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO dispatch_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO dispatch_app;
EOSQL

psql -v ON_ERROR_STOP=1 -U postgres -d payment <<-EOSQL
    GRANT ALL ON SCHEMA public TO payment_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO payment_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO payment_app;
EOSQL

echo "Database initialization completed successfully"