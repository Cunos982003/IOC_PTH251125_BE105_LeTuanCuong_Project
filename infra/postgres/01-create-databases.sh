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
# Create/update roles with passwords from environment variables
# Using shell expansion for passwords (works in fresh deploy, handles updates on rerun)
psql -v ON_ERROR_STOP=1 -U postgres -d postgres <<-EOSQL
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'user_app') THEN
            CREATE ROLE user_app WITH LOGIN PASSWORD '${USER_DB_PASSWORD}';
        ELSE
            ALTER ROLE user_app WITH PASSWORD '${USER_DB_PASSWORD}';
        END IF;
    END
    \$\$;
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'location_app') THEN
            CREATE ROLE location_app WITH LOGIN PASSWORD '${LOCATION_DB_PASSWORD}';
        ELSE
            ALTER ROLE location_app WITH PASSWORD '${LOCATION_DB_PASSWORD}';
        END IF;
    END
    \$\$;
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'dispatch_app') THEN
            CREATE ROLE dispatch_app WITH LOGIN PASSWORD '${DISPATCH_DB_PASSWORD}';
        ELSE
            ALTER ROLE dispatch_app WITH PASSWORD '${DISPATCH_DB_PASSWORD}';
        END IF;
    END
    \$\$;
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'payment_app') THEN
            CREATE ROLE payment_app WITH LOGIN PASSWORD '${PAYMENT_DB_PASSWORD}';
        ELSE
            ALTER ROLE payment_app WITH PASSWORD '${PAYMENT_DB_PASSWORD}';
        END IF;
    END
    \$\$;
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
