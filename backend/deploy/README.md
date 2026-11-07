# Deploy on Vultr (Ubuntu 24.04)

```bash
apt update && apt install -y python3-venv caddy git
git clone <repo> /opt/smart-eyes && cd /opt/smart-eyes/backend
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
cp .env.example .env && nano .env            # fill keys
cp deploy/smart-eyes.service /etc/systemd/system/ && systemctl enable --now smart-eyes
cp deploy/Caddyfile /etc/caddy/Caddyfile && nano /etc/caddy/Caddyfile   # set domain
systemctl reload caddy
ufw allow 80,443/tcp   # and open 80/443 in the Vultr firewall group
```

Check: `curl -H "X-Secret: $S" -F image=@test.jpg -F mode=describe https://<domain>/frame`
Logs: `journalctl -u smart-eyes -f`
