DO
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'root') THEN
        CREATE ROLE root LOGIN PASSWORD 'root';
    ELSE
        ALTER ROLE root WITH LOGIN PASSWORD 'root';
    END IF;
END
$$;

SELECT 'CREATE DATABASE test OWNER root'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'test')
\gexec

GRANT ALL PRIVILEGES ON DATABASE test TO root;
