# IT, Linux, Git a sítě

## Linux – soubory a procesy
ls -la (výpis včetně skrytých), cd, pwd, cp -r, mv, rm -r (pozor, nevratné), mkdir -p, find . -name "*.txt", grep -rn "text" ., du -sh *, df -h (místo na discích), chmod 644 soubor / chmod +x skript, chown uživatel:skupina. Procesy: ps aux, top/htop, kill PID, systemctl status|start|stop|restart služba, journalctl -u služba -f (logy).

## Git
git clone URL, git status, git add -A, git commit -m "zpráva", git push -u origin větev, git pull, git checkout -b nová-větev, git log --oneline, git diff, git stash / git stash pop. Přenos commitů bez přístupu k síti: git bundle create soubor.bundle větev a na druhé straně git fetch soubor.bundle větev:větev. Vrácení necommitnutých změn souboru: git restore soubor.

## Sítě
Privátní rozsahy IPv4: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16; Tailscale/CGNAT 100.64.0.0/10. Užitečné příkazy: ping host, curl -I URL (hlavičky), ip a (adresy), ss -tlnp (naslouchající porty), nslookup/dig doména. Porty: 22 SSH, 80 HTTP, 443 HTTPS, 8080 často vývojové servery, 11434 Ollama, 8080 llama-server (výchozí).

## Docker
docker run -d -p 8080:8080 --name jméno obraz, docker ps, docker logs -f jméno, docker exec -it jméno sh, docker compose up -d, docker system prune (úklid). Proměnné prostředí: -e KLÍČ=hodnota. Svazky: -v /cesta/host:/cesta/kontejner.

## Bezpečnost
Hesla a API klíče neukládej do repozitáře; používej správce tajemství (GitHub Secrets, Android Keystore). SSH klíč: ssh-keygen -t ed25519. Aktualizace systému pravidelně. Princip nejmenších oprávnění: každá aplikace jen ta práva, která nutně potřebuje.
