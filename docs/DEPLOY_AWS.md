# Deploying Picsou on AWS

This guide walks you through deploying Picsou on a single EC2 instance with Docker Compose, HTTPS via Caddy, and proper secrets management. This is the simplest and cheapest production setup (~$5-10/month on a `t3.small`).

> **Picsou is single-user by design.** If you need separate instances (e.g. one for you, one for a family member), repeat the setup on a second instance or run two stacks on the same machine with different subdomains (covered in the [Multi-instance on one server](#multi-instance-on-one-server) section).

---

## Architecture on AWS

```
Internet
   │
   ▼
┌─────────────────────────────────────────────────────────────┐
│  EC2 instance (t3.small, Ubuntu 24.04)                      │
│                                                             │
│  ┌─────────┐    ┌───────────┐    ┌──────────┐    ┌──────┐  │
│  │  Caddy   │───▶│  Frontend │───▶│ Backend  │───▶│  DB  │  │
│  │ (HTTPS)  │    │  (Nginx)  │    │ (Spring) │    │(PG16)│  │
│  └─────────┘    └───────────┘    └──────────┘    └──────┘  │
│  :443 ←── only open port                                    │
│                                                             │
│  EBS volume (gp3, encrypted) ── PostgreSQL data             │
└─────────────────────────────────────────────────────────────┘
```

Caddy handles automatic HTTPS (Let's Encrypt) and reverse-proxies to the Picsou frontend container. All inter-container traffic stays on the Docker bridge network.

---

## Prerequisites

- An **AWS account**
- A **domain name** (e.g. `picsou.example.com`) with DNS you can control
- A local machine with `ssh` and `git` installed
- (Optional) An [Enable Banking](https://enablebanking.com/) account for bank sync

---

## Step 1 — Launch an EC2 instance

### 1.1 Choose your instance

| Setting | Value |
|---------|-------|
| AMI | Ubuntu Server 24.04 LTS (arm64 or amd64) |
| Instance type | `t3.small` (2 vCPU, 2 GB RAM) or `t4g.small` (ARM, cheaper) |
| Storage | 20 GB gp3, **Encrypted** (check the box) |
| Key pair | Create or select an SSH key pair |

### 1.2 Security group

Create a security group with **only** these inbound rules:

| Port | Protocol | Source | Purpose |
|------|----------|--------|---------|
| 22 | TCP | Your IP only (`x.x.x.x/32`) | SSH |
| 443 | TCP | `0.0.0.0/0` | HTTPS |
| 80 | TCP | `0.0.0.0/0` | HTTP (Caddy redirects to HTTPS) |

Do **not** open 5173, 8080, or 5432. Those stay internal.

### 1.3 Elastic IP

Allocate an Elastic IP and associate it with your instance so the IP doesn't change on reboot.

### 1.4 DNS

Create an A record in your DNS provider:

```
picsou.example.com  →  <your-elastic-ip>
```

Wait for propagation (usually a few minutes).

---

## Step 2 — Server setup

SSH into your instance:

```bash
ssh -i your-key.pem ubuntu@picsou.example.com
```

### 2.1 System updates and Docker

```bash
# Update system
sudo apt update && sudo apt upgrade -y

# Install Docker
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER

# Install Docker Compose plugin
sudo apt install -y docker-compose-plugin

# Log out and back in for group changes
exit
```

SSH back in, then verify:

```bash
docker --version
docker compose version
```

### 2.2 Enable automatic security updates

```bash
sudo apt install -y unattended-upgrades
sudo dpkg-reconfigure -plow unattended-upgrades
```

### 2.3 Harden SSH

Edit `/etc/ssh/sshd_config`:

```
PermitRootLogin no
PasswordAuthentication no
MaxAuthTries 3
```

Then `sudo systemctl restart sshd`.

---

## Step 3 — Deploy Picsou

### 3.1 Clone and configure

```bash
cd ~
git clone <your-repo-url> picsou
cd picsou
cp .env.example .env
```

### 3.2 Generate secrets

```bash
# Strong PostgreSQL password
echo "POSTGRES_PASSWORD=$(openssl rand -base64 24)" >> .env.generated

# JWT signing key (min 32 chars)
echo "JWT_SECRET=$(openssl rand -base64 48)" >> .env.generated

# App login password — pick a strong one
APP_PASSWORD="YourStrongPasswordHere"
# Generate bcrypt hash (cost 12)
sudo apt install -y apache2-utils
HASH=$(htpasswd -bnBC 12 "" "$APP_PASSWORD" | tr -d ':\n')
echo "APP_PASSWORD_HASH=$HASH" >> .env.generated

# Review and merge into .env
cat .env.generated
```

Edit `.env` and replace the placeholder values with the generated ones. Set your username:

```bash
nano .env
```

Key values to set:

```env
POSTGRES_PASSWORD=<generated>
JWT_SECRET=<generated>
APP_USERNAME=<your-username>
APP_PASSWORD_HASH=<generated-bcrypt-hash>
ALLOWED_ORIGINS=https://picsou.example.com
SPRING_PROFILES_ACTIVE=prod
```

Clean up:

```bash
rm .env.generated
```

### 3.3 Enable Banking keys (optional)

If you want bank sync:

```bash
mkdir -p secrets
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out secrets/enablebanking.pem
chmod 600 secrets/enablebanking.pem

# Extract public key to upload to Enable Banking dashboard
openssl rsa -pubout -in secrets/enablebanking.pem -out enablebanking_public.pem
cat enablebanking_public.pem
```

Upload `enablebanking_public.pem` to your Enable Banking dashboard, then add to `.env`:

```env
ENABLEBANKING_APPLICATION_ID=<from-dashboard>
ENABLEBANKING_KEY_ID=<from-dashboard>
ENABLEBANKING_REDIRECT_URI=https://picsou.example.com/sync/callback
ENABLEBANKING_PRIVATE_KEY_PATH=/run/secrets/enablebanking.pem
```

### 3.4 Secure file permissions

```bash
chmod 600 .env
chmod 600 secrets/*
```

---

## Step 4 — Set up HTTPS with Caddy

Caddy automatically provisions and renews Let's Encrypt certificates.

### 4.1 Create a Caddy config

```bash
mkdir -p ~/picsou/caddy
cat > ~/picsou/caddy/Caddyfile << 'EOF'
picsou.example.com {
    reverse_proxy frontend:8080

    header {
        Strict-Transport-Security "max-age=31536000; includeSubDomains; preload"
        X-Content-Type-Options "nosniff"
        X-Frame-Options "DENY"
        Referrer-Policy "strict-origin-when-cross-origin"
    }
}
EOF
```

Replace `picsou.example.com` with your actual domain.

### 4.2 Add Caddy to docker-compose

Create a `docker-compose.override.yml`:

```yaml
services:
  caddy:
    image: caddy:2-alpine
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./caddy/Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
    depends_on:
      - frontend
    networks:
      - picsou-net

  # Override: remove direct port exposure on frontend
  frontend:
    ports: !override []

volumes:
  caddy_data:
  caddy_config:
```

> **Note:** The `ports: !override []` syntax removes the `5173:8080` mapping from the base `docker-compose.yml` so only Caddy is exposed. If your Docker Compose version doesn't support `!override`, manually edit `docker-compose.yml` and remove the `ports` block from the `frontend` service instead.

### 4.3 Launch

```bash
cd ~/picsou
docker compose up -d --build
```

Verify all containers are running:

```bash
docker compose ps
docker compose logs -f caddy   # Watch for successful certificate issuance
```

Visit `https://picsou.example.com` — you should see the login page with a valid HTTPS certificate.

---

## Step 5 — Security hardening

### 5.1 UFW firewall (defense in depth)

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow ssh
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
```

### 5.2 Fail2ban for SSH

```bash
sudo apt install -y fail2ban
sudo systemctl enable fail2ban
sudo systemctl start fail2ban
```

### 5.3 Verify nothing leaks

From your local machine, confirm that only 80/443 are reachable:

```bash
nmap -Pn picsou.example.com
# Should show: 80/tcp open, 443/tcp open — nothing else
```

### 5.4 Docker log rotation

Prevent logs from filling the disk:

```bash
sudo cat > /etc/docker/daemon.json << 'EOF'
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  }
}
EOF
sudo systemctl restart docker
```

---

## Step 6 — Backups

### 6.1 Automated PostgreSQL backups

Create a backup script:

```bash
cat > ~/picsou/backup.sh << 'SCRIPT'
#!/bin/bash
set -euo pipefail

BACKUP_DIR="$HOME/picsou-backups"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
mkdir -p "$BACKUP_DIR"

# Dump database
docker compose -f "$HOME/picsou/docker-compose.yml" exec -T db \
  pg_dump -U picsou picsou | gzip > "$BACKUP_DIR/picsou_${TIMESTAMP}.sql.gz"

# Keep only last 30 days
find "$BACKUP_DIR" -name "*.sql.gz" -mtime +30 -delete

echo "Backup complete: picsou_${TIMESTAMP}.sql.gz"
SCRIPT

chmod +x ~/picsou/backup.sh
```

### 6.2 Schedule daily backups

```bash
crontab -e
# Add this line:
0 3 * * * /home/ubuntu/picsou/backup.sh >> /home/ubuntu/picsou-backups/backup.log 2>&1
```

### 6.3 (Recommended) Copy backups to S3

```bash
# Install AWS CLI
sudo apt install -y awscli

# Configure (use an IAM user with only S3 PutObject permission)
aws configure

# Add to backup.sh, after the gzip line:
aws s3 cp "$BACKUP_DIR/picsou_${TIMESTAMP}.sql.gz" s3://your-backup-bucket/picsou/
```

### 6.4 Restore from backup

```bash
gunzip -c picsou_20260326_030000.sql.gz | \
  docker compose exec -T db psql -U picsou picsou
```

---

## Step 7 — Updates

To pull and deploy a new version:

```bash
cd ~/picsou
git pull
docker compose up -d --build
```

Flyway handles database migrations automatically on startup.

---

## Multi-instance on one server

To run separate Picsou instances (e.g. one for you, one for your dad):

### Option A: Two subdomains on one EC2

```
picsou.example.com      → your instance
picsou-dad.example.com  → dad's instance
```

1. Clone the repo twice:

```bash
git clone <repo-url> ~/picsou-me
git clone <repo-url> ~/picsou-dad
```

2. Each gets its own `.env` with **different** credentials, database passwords, and JWT secrets.

3. Use a single shared Caddy instance. Update the Caddyfile:

```
picsou.example.com {
    reverse_proxy localhost:5173
}

picsou-dad.example.com {
    reverse_proxy localhost:5174
}
```

4. In `~/picsou-dad/docker-compose.yml`, change the network name and all port mappings to avoid conflicts:
   - Frontend: `5174:8080`
   - Database volume: `pgdata_dad`
   - Network: `picsou-dad-net`

5. Run Caddy outside of Docker Compose:

```bash
sudo apt install -y caddy
sudo nano /etc/caddy/Caddyfile   # Add both domains
sudo systemctl reload caddy
```

### Option B: Two separate EC2 instances

Simpler to manage, completely isolated. Repeat the entire guide for each instance with different subdomains. Cost: ~$10-20/month total.

---

## Cost estimate

| Resource | Monthly cost |
|----------|-------------|
| EC2 `t3.small` (on-demand) | ~$15 |
| EC2 `t3.small` (1yr reserved) | ~$8 |
| EC2 `t4g.small` (ARM, on-demand) | ~$12 |
| EBS 20 GB gp3 | ~$1.60 |
| Elastic IP (while attached) | Free |
| Data transfer (light usage) | ~$0-1 |
| **Total (reserved ARM)** | **~$10/month** |

> **Tip:** For the cheapest option, use a `t4g.micro` (ARM, 1 GB RAM) — it may work for light usage but monitor memory. Picsou's backend JVM is configured to use up to 75% of container RAM.

---

## Monitoring

### Basic health check

```bash
# From your local machine or a cron job
curl -sf https://picsou.example.com/api/actuator/health || echo "PICSOU DOWN"
```

### CloudWatch (optional)

Enable detailed monitoring on your EC2 instance for CPU/memory/disk alerts. Install the CloudWatch agent:

```bash
sudo apt install -y amazon-cloudwatch-agent
```

### Uptime monitoring (free tier options)

- [UptimeRobot](https://uptimerobot.com/) — free for 50 monitors, 5-min intervals
- [Healthchecks.io](https://healthchecks.io/) — monitors cron jobs (pair with backup script)

---

## Troubleshooting

### Caddy won't get a certificate

- Verify DNS: `dig picsou.example.com` should return your Elastic IP
- Ensure ports 80 and 443 are open in the Security Group
- Check logs: `docker compose logs caddy`

### Backend won't start

```bash
docker compose logs backend
```

Common causes:
- Missing or malformed `JWT_SECRET` (must be at least 32 characters)
- Invalid `APP_PASSWORD_HASH` (must be a valid bcrypt hash starting with `$2a$`)
- Database not ready — the healthcheck should prevent this, but check `docker compose logs db`

### Database connection refused

```bash
docker compose logs db
docker compose exec db pg_isready -U picsou
```

If the volume was corrupted, restore from backup (Step 6.4).

### Out of memory

If the JVM or containers are getting OOM-killed:

```bash
# Check system memory
free -h

# Check Docker stats
docker stats --no-stream
```

Consider upgrading to `t3.medium` (4 GB) or adding swap:

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

---

## Security checklist

Before going live, verify:

- [ ] `.env` has strong, unique passwords (not the example values)
- [ ] `.env` file permissions are `600` (only owner can read)
- [ ] `secrets/` directory permissions are `600`
- [ ] Security group only allows 22 (your IP), 80, and 443
- [ ] SSH uses key-based auth only (password auth disabled)
- [ ] HTTPS is working (padlock in browser)
- [ ] `ALLOWED_ORIGINS` matches your actual domain (`https://...`)
- [ ] Enable Banking redirect URI uses `https://`
- [ ] UFW firewall is enabled
- [ ] Fail2ban is running
- [ ] Backups are running (check `~/picsou-backups/backup.log`)
- [ ] Docker log rotation is configured
- [ ] No ports other than 80/443 are publicly reachable (`nmap` check)
