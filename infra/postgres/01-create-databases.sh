#!/bin/bash
# PostgreSQL database initialization script
# Creates databases and roles for each service
# Run as root postgres user during container initialization

set -e

# Wait for postgres to be ready
until pg_isready -U postgres -d platform; do
  sleep 1
done

# Create databases
psql -v ON_ERROR_STOP=1 -U postgres -d platform <<-EOSQL
    CREATE DATABASE "user";
    CREATE DATABASE location;
    CREATE DATABASE dispatch;
    CREATE DATABASE payment;
EOSQL

# Create roles with passwords from environment variables
# Use psql variables to avoid quoting issues in heredoc
psql -v ON_ERROR_STOP=1 -U postgres -d platform \
    -v user_pwd="${USER_DB_PASSWORD}" \
    -v location_pwd="${LOCATION_DB_PASSWORD}" \
    -v dispatch_pwd="${DISPATCH_DB_PASSWORD}" \
    -v payment_pwd="${PAYMENT_DB_PASSWORD}" \
    <<-EOSQL
    CREATE ROLE user_app WITH LOGIN PASSWORD :'user_pwd';
    CREATE ROLE location_app WITH LOGIN PASSWORD :'location_pwd';
    CREATE ROLE dispatch_app WITH LOGIN PASSWORD :'dispatch_pwd';
    CREATE ROLE payment_app WITH LOGIN PASSWORD :'payment_pwd';
EOSQL

# Grant privileges
psql -v ON_ERROR_STOP=1 -U postgres -d platform <<-EOSQL
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