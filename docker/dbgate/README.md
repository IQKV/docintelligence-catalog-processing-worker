# DbGate Configuration - Microservice Project Layout Template

This directory contains the DbGate database administration tool configuration for the microservice project layout template.

## Pre-configured Connections

The `connections.jsonl` file contains pre-configured connections template for microservice infrastructure:

### 1. PostgreSQL - Catalog Processing Worker

- **ID**: `postgres-catalog`
- **Server**: `postgres-catalog:5432`
- **Database**: `catalogservice`
- **User**: `svc_catalog_dba`
- **Engine**: `postgres@dbgate-plugin-postgres`

### 2. RabbitMQ - Catalog Processing Worker

- **ID**: `rabbitmq-catalog`
- **Server**: `rabbitmq-catalog:15672`
- **User**: `svc_catalog_rmq`
- **Engine**: `rabbitmq@dbgate-plugin-rabbitmq`

## Template Usage

When creating a new microservice from this template:

1. **Replace `catalog`** with your actual service name in `connections.jsonl`
2. **Update credentials** if you changed them from defaults
3. **Add additional connections** as needed (Redis, MinIO, etc.)

### Example: Creating "payment-service"

Replace all occurrences of `catalog` with `payment`:

```json
{"_id":"postgres-payment","engine":"postgres@dbgate-plugin-postgres","server":"postgres-payment","port":5432,"user":"svc_payment_dba","password":"svc_payment_dba","database":"payment","displayName":"PostgreSQL - Payment Service"}
{"_id":"rabbitmq-payment","engine":"rabbitmq@dbgate-plugin-rabbitmq","server":"rabbitmq-payment","port":15672,"user":"svc_payment_rmq","password":"svc_payment_rmq","displayName":"RabbitMQ - Payment Service"}
```

## Usage

### Start Infrastructure with DbGate

```bash
# Start infrastructure only (for IDE development)
docker compose up -d

# Access DbGate at: http://localhost:3100
```

**Note**: DbGate runs on port **3100** (not 3000) to avoid conflict with Grafana.

### Start Full Stack with DbGate

```bash
# Start infrastructure + service
docker compose -f compose.container.yaml up -d

# Access DbGate at: http://localhost:3100
```

### Access DbGate UI

Once the services are running, open your browser to:

**http://localhost:3100**

All connections are pre-configured and ready to use.

## What You Can Do with DbGate

### PostgreSQL Database

- Browse schemas, tables, and views
- Execute SQL queries with syntax highlighting
- Import/export data (CSV, JSON, SQL)
- View and edit table data
- Analyze table structure and relationships
- Create ER diagrams

### RabbitMQ

- View queues and their message counts
- Monitor exchange bindings
- Check connection statistics
- View queue configurations
- Monitor message rates

## Adding Additional Services

As you extend your microservice, you may want to add connections for:

### Redis Cache

```json
{ "_id": "redis-cache", "engine": "redis@dbgate-plugin-redis", "server": "redis-catalog", "port": 6379, "displayName": "Redis - Cache" }
```

### MinIO S3 Storage

```json
{ "_id": "minio-s3", "engine": "s3@dbgate-plugin-s3", "server": "minio-catalog", "port": 9000, "user": "accesskey", "password": "secretkey", "displayName": "MinIO - S3 Storage" }
```

### Additional PostgreSQL Database

```json
{
    "_id": "postgres-analytics",
    "engine": "postgres@dbgate-plugin-postgres",
    "server": "postgres-analytics",
    "port": 5432,
    "user": "analytics_user",
    "password": "analytics_pass",
    "database": "analytics",
    "displayName": "PostgreSQL - Analytics"
}
```

## Updating Credentials

If you change credentials in your compose files, update `connections.jsonl` accordingly:

1. Edit `docker/dbgate/connections.jsonl`
2. Update the `user` and `password` fields
3. Restart DbGate:

```bash
docker compose restart dbgate
```

## Port Configuration

DbGate is configured to run on **port 3100** instead of the default 3000:

```yaml
ports:
    - "3100:3000" # DbGate Web UI (3100 to avoid conflict with Grafana)
```

This avoids port conflicts with:

- Grafana (port 3000)
- Other services that commonly use port 3000

If you need to change the port, edit `compose.base.yaml`:

```yaml
ports:
    - "YOUR_PORT:3000" # DbGate Web UI
```

## Troubleshooting

### DbGate won't start

```bash
# Check logs
docker logs foundation-catalog-dbgate-dev

# Verify connections file exists
ls docker/dbgate/connections.jsonl

# Restart DbGate
docker compose restart dbgate
```

### Can't connect to PostgreSQL

```bash
# Verify PostgreSQL is running and healthy
docker ps --filter name=foundation-catalog-postgres-dev

# Check PostgreSQL logs
docker logs foundation-catalog-postgres-dev

# Test connection from DbGate container
docker exec foundation-catalog-dbgate-dev ping postgres-catalog
```

### Can't connect to RabbitMQ

```bash
# Verify RabbitMQ is running
docker ps --filter name=foundation-catalog-rabbitmq-dev

# Check RabbitMQ logs
docker logs foundation-catalog-rabbitmq-dev

# Check management plugin is enabled
docker exec foundation-catalog-rabbitmq-dev rabbitmq-plugins list
```

### Port 3100 already in use

If port 3100 is already in use on your system:

1. Edit `compose.base.yaml`
2. Change the port mapping:
    ```yaml
    ports:
        - "3101:3000" # Or any other available port
    ```
3. Restart services

### Reset DbGate Data

```bash
# Stop and remove DbGate
docker compose stop dbgate
docker compose rm -f dbgate

# Remove volume
docker volume rm docintelligence_catalog_dbgate_data_dev

# Restart
docker compose up -d dbgate
```

## Security Notes

⚠️ **Important**: This configuration is for local development only.

- Credentials are stored in plaintext in `connections.jsonl`
- Do not commit real production credentials to version control
- DbGate port is exposed to localhost only
- Authentication is enabled (`LOGINS=1`)
- Change default credentials before deploying to shared environments

## Compose File Configuration

DbGate is included in all three compose configurations:

- ✅ `compose.yaml` - Infrastructure only (use with IDE)
- ✅ `compose.container.yaml` - Full stack (infrastructure + service)
- ✅ `compose.base.yaml` - Base definitions

When you use this template for a new service, DbGate is automatically included.

## Template Customization Checklist

When creating a new microservice from this template:

- [ ] Replace `catalog` with your service name in `connections.jsonl`
- [ ] Update database credentials if changed from defaults
- [ ] Update RabbitMQ credentials if changed from defaults
- [ ] Add connections for additional infrastructure (Redis, MinIO, etc.)
- [ ] Update service display names for clarity
- [ ] Test DbGate connectivity after first `docker compose up`

## Supported Database Engines

Add more connections as your microservice grows:

- `postgres@dbgate-plugin-postgres` - PostgreSQL
- `mysql@dbgate-plugin-mysql` - MySQL
- `mariadb@dbgate-plugin-mariadb` - MariaDB
- `mongo@dbgate-plugin-mongo` - MongoDB
- `redis@dbgate-plugin-redis` - Redis
- `rabbitmq@dbgate-plugin-rabbitmq` - RabbitMQ
- `s3@dbgate-plugin-s3` - S3-compatible storage

## More Information

- [DbGate Official Documentation](https://dbgate.org/docs/)
- [DbGate GitHub Repository](https://github.com/dbgate/dbgate)
- [Supported Database Engines](https://dbgate.org/docs/databases.html)

## Tips

1. **Query History**: DbGate automatically saves your SQL query history
2. **Keyboard Shortcuts**: Press `Ctrl+Enter` to execute queries
3. **Export Data**: Right-click tables to export as CSV, JSON, or SQL
4. **Dark Mode**: Available in settings (top-right corner)
5. **Schema Diagram**: View ER diagrams to understand table relationships
6. **Multiple Tabs**: Open multiple database connections in separate tabs
7. **Import Data**: Drag and drop CSV/JSON files to import data

---

**This is a template configuration.** Customize it for your specific microservice needs.
