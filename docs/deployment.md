# Deployment

AlphaLedger deploys to one Linux server with Docker Compose. `main` is the deployment branch: a push runs CI, a green CI publishes one image per service to GHCR tagged with the commit SHA, and the release workflow can then run `infra/deploy.sh` on the server.

Only Caddy is public (ports 80 and 443). It serves HTTPS and forwards to the dashboard, which proxies `/api` to the gateway. Everything else stays on the Compose network; Prometheus and Grafana bind to `127.0.0.1`.

## First-time setup

Use a server with at least 4 vCPUs, 16 GB RAM and 50 GB of disk, with Docker Engine and Compose 2.24 or newer.

1. Point the domain's DNS A record at the server, and open ports 22, 80 and 443 only.
2. Clone the repository into `~/alphaledger`.
3. Create the settings file:
   ```sh
   cd ~/alphaledger
   cp infra/production.env.example infra/.env
   chmod 600 infra/.env
   ```
   Fill in every value. Never change `POSTGRES_PASSWORD` after the first start without also changing it inside Postgres.
4. If the GHCR packages are private, sign in once with a token that has `read:packages`:
   ```sh
   docker login ghcr.io -u shravanib-debug
   ```
5. Deploy a released commit:
   ```sh
   git checkout <commit-sha>
   infra/deploy.sh <commit-sha>
   ```

## Automatic deploys

In the GitHub repository settings, add:

| Kind | Name | Value |
|---|---|---|
| Secret | `DEPLOY_HOST` | Server address |
| Secret | `DEPLOY_USER` | SSH user allowed to run Docker |
| Secret | `DEPLOY_SSH_KEY` | Private key for that user |
| Variable | `DEPLOY_ENABLED` | `true` |

Also turn on branch protection for `main` and require the `unit`, `integration` and `frontend` checks.

## Rollback

`infra/deploy.sh` waits for every health check and returns to the previously deployed tag if one fails. To roll back by hand, deploy an older SHA:

```sh
git checkout <older-sha>
infra/deploy.sh <older-sha>
```

Flyway migrations are not undone. Reverting to code older than a migration only works if that code can run on the newer schema, so check this first and restore a backup if it can't.

## Backups

Postgres holds all durable data. Schedule this daily and copy the file off the server:

```sh
docker compose -f infra/docker-compose.yml -f infra/docker-compose.prod.yml exec -T postgres \
  pg_dumpall -U alphaledger | gzip > "alphaledger-$(date +%F).sql.gz"
```

The command needs `IMAGE_TAG` set (`export IMAGE_TAG=$(cat infra/.deployed-tag)`). To restore, start Postgres on an empty volume and pipe the unzipped dump into `psql -U alphaledger postgres`.

## Monitoring

Open Grafana through a tunnel: `ssh -L 3001:localhost:3001 <server>`, then visit `http://localhost:3001`.

## Limits of this setup

- One server with one Postgres and one Kafka broker: no high availability, and maintenance means downtime.
- Sign-in uses the seeded demo accounts, with the password from `DEMO_USER_PASSWORD`. All data is synthetic.
- Market data runs in `OFFLINE` mode.
- Mail goes to the internal MailHog sink until `MAIL_HOST` points at a real relay.
