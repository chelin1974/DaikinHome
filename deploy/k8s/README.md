# Deploying the Daikin bridge to Kubernetes

A complete walkthrough: build an image, get it onto the cluster, deploy, pair with the Home
app, and run it day to day. If you only want the short version, jump to
[The whole thing, in order](#the-whole-thing-in-order).

> **Read [Before you start](#before-you-start) first.** HomeKit needs mDNS multicast, which
> a normal pod cannot do. That one fact decides whether this can work on your cluster at
> all, and it is better to find out now than after three failed deploys.

---

## Before you start

### This needs a node on your home LAN

HomeKit discovery and resolution are mDNS: multicast to `224.0.0.251:5353` on the local
link. A pod on a CNI overlay can neither send nor receive it. The Home app would never find
the bridge, and a bridge that was already paired would stop resolving and show every
accessory as "No Response".

So the pod runs with `hostNetwork: true`, directly on a node's LAN interface. Three things
follow, and all three are hard requirements:

1. **A node must be on the same LAN/VLAN as your Apple TV or HomePod.** mDNS does not cross
   subnets. A cloud cluster cannot host this at all.
2. **That node's ports 8080 and 51826 must be free.**
3. **The namespace must allow `hostNetwork`.** The `baseline` and `restricted` Pod Security
   Standards both forbid it; `namespace.yaml` sets `enforce: privileged` for this reason.

Check you have a suitable node:

```bash
kubectl get nodes -o wide
```

Look for a node whose `INTERNAL-IP` is on your home subnet (`192.168.x.x`, `10.x.x.x`). If
none is, stop here — this cannot work on this cluster, and the honest options are a Pi or
mini PC joined to it, or running the bridge outside Kubernetes with
`daikin-home.service`.

### What you need

- `kubectl` pointing at the cluster, and permission to create namespaces.
- A default `StorageClass` (`kubectl get storageclass`). On k3s that is `local-path`.
- Your three Daikin credentials — see the main [README](../../README.md) for where they
  come from in the Daikin One app.
- Somewhere to publish a container image, below.

---

## Step 1 — get an image onto the cluster

### Option A: let CI build it (recommended)

`.github/workflows/image.yml` builds `linux/amd64` and `linux/arm64` and pushes to
`ghcr.io/chelin1974/daikin-home` on every push to `main`, and on a `v*` tag.

```bash
git push origin main            # builds and pushes :latest
git tag v1.0.0 && git push origin v1.0.0   # builds and pushes :1.0.0
```

### Option B: build it yourself

Needs `docker buildx`. Build for the architecture your **node** runs, not your laptop — a
Raspberry Pi is `arm64`:

```bash
echo "$GITHUB_PAT" | docker login ghcr.io -u chelin1974 --password-stdin
docker buildx build --platform linux/arm64,linux/amd64 \
  -t ghcr.io/chelin1974/daikin-home:latest --push .
```

The PAT needs the `write:packages` scope.

### Then make the image pullable

**GitHub Container Registry packages are private by default**, and a private package fails
with `ImagePullBackOff` and `denied`. Pick one:

- **Make the package public** — Profile → Packages → `daikin-home` → Package settings →
  Change visibility → Public. Simplest, and the image contains nothing secret: credentials
  arrive from a Secret at runtime.
- **Or create a pull secret:**

  ```bash
  kubectl -n daikin-home create secret docker-registry ghcr-pull \
    --docker-server=ghcr.io \
    --docker-username=chelin1974 \
    --docker-password="$GITHUB_PAT"
  ```

  Then uncomment the `imagePullSecrets` block in `deployment.yaml`.

---

## Step 2 — label the node

The pod is pinned to the node on your LAN. Nothing schedules until this label exists:

```bash
kubectl label node <node-name> homekit=true
```

Use one node only. Two labelled nodes and a rescheduled pod means a bridge that moves
house, which breaks pairing.

## Step 3 — create the namespace and credentials

```bash
kubectl apply -f deploy/k8s/namespace.yaml
```

Then the credentials. If you already have a `daikin.config.json` for running the bridge
locally, the helper script reads it and creates the Secret for you:

```bash
./deploy/k8s/create-secret.sh                    # reads ./daikin.config.json
./deploy/k8s/create-secret.sh --dry-run          # list the keys, send nothing
./deploy/k8s/create-secret.sh --config ~/daikin.config.json
```

It translates each setting to the environment variable Spring binds it to, skips the
example file's `_comment` keys and any empty values, and skips `storagePath` and
`bindAddress` — the Deployment supplies those itself, correctly for the cluster, and a
value from your laptop would override both with something wrong. It validates the same
rules the bridge does, so a half-filled config fails here with an explanation rather than
in a `CrashLoopBackOff`. Re-running it updates the Secret in place.

It passes values to `kubectl` in a mode-600 temporary file rather than as `--from-literal`
arguments, because command-line arguments are visible to every user on the machine through
`ps`. If you would rather do it by hand, bear that in mind:

```bash
kubectl -n daikin-home create secret generic daikin-home-credentials \
  --from-literal=DAIKIN_BACKEND=skyport \
  --from-literal=DAIKIN_EMAIL='you@example.com' \
  --from-literal=DAIKIN_PASSWORD='...'
```

Any `daikin.*` setting from `application.yml` can be overridden the same way:
`DAIKIN_POLL_INTERVAL_SECONDS`, `DAIKIN_BRIDGE_NAME`, `DAIKIN_PROMETHEUS_URL`.
`secret.example.yaml` is a template to read, not to apply.

## Step 4 — deploy

```bash
kubectl apply -k deploy/k8s
kubectl -n daikin-home rollout status deploy/daikin-home
```

If you do not run an ingress controller, drop `ingress.yaml` from
`kustomization.yaml` first — otherwise you create an Ingress nothing serves.

## Step 5 — pair with the Home app

The setup code is printed once at startup, and only while the bridge is unpaired:

```bash
kubectl -n daikin-home logs deploy/daikin-home | grep -A2 'setup code'
```

It is also on the API until the first controller pairs:

```bash
curl -s http://<node-ip>:8080/api/bridge
```

In the Home app: **Add Accessory → More options… → pick the bridge → enter the code**. The
thermostat and its outdoor sensor arrive together.

## Step 6 — check it

```bash
curl -s http://<node-ip>:8080/api/thermostats          # your thermostats
curl -s http://<node-ip>:8080/actuator/health          # UP, and discoverable: true
```

`discoverable: true` is the one worth looking at: it means the mDNS advertisement is alive,
which is what HomeKit actually depends on.

---

## The whole thing, in order

```bash
kubectl get nodes -o wide                       # find a node on your home LAN
kubectl apply -f deploy/k8s/namespace.yaml
kubectl label node <node-name> homekit=true
./deploy/k8s/create-secret.sh                   # from your existing daikin.config.json
kubectl apply -k deploy/k8s
kubectl -n daikin-home rollout status deploy/daikin-home
kubectl -n daikin-home logs deploy/daikin-home | grep -A2 'setup code'
```

---

## Running it day to day

### Reaching the API

With `hostNetwork` it is already on the node: `http://<node-ip>:8080`. You do not need a
Service or an Ingress for that; they exist for a stable in-cluster name
(`http://daikin-home.daikin-home.svc:8080`) and for a hostname via `ingress.yaml`.

| | |
| --- | --- |
| `GET /api/thermostats` | every thermostat and its last polled state |
| `PUT /api/thermostats/{id}` | change mode and setpoints |
| `POST /api/thermostats/{id}/refresh` | force a read instead of waiting for the poll |
| `GET /api/bridge` | pairing state and the setup code |
| `GET /actuator/health` | health, including `discoverable` |

Note this bridge serves a **REST API, not an HTML dashboard** — there is no page to look
at. **And none of it is authenticated**: anyone who reaches port 8080 can turn your heating
off. Keep it on your LAN and off any public ingress.

### Upgrading

```bash
kubectl -n daikin-home set image deploy/daikin-home daikin-home=ghcr.io/chelin1974/daikin-home:1.1.0
kubectl -n daikin-home rollout status deploy/daikin-home
```

On `:latest` the tag does not change, so force a re-pull:

```bash
kubectl -n daikin-home rollout restart deploy/daikin-home
```

Expect a few seconds of downtime: the strategy is `Recreate`, not `RollingUpdate`, because
two pods would fight over the HomeKit port. Pairing survives — it lives on the PVC.

### Changing configuration

```bash
$EDITOR daikin.config.json
./deploy/k8s/create-secret.sh                   # updates the Secret in place
kubectl -n daikin-home rollout restart deploy/daikin-home
```

The Secret is read at startup, so the restart is required.

### Back up the pairing state

`daikin-home-state` holds the bridge's HomeKit identity and every paired controller's key.
Lose it and you remove and re-add the bridge in the Home app, re-doing every room
assignment and automation. Back it up:

```bash
kubectl -n daikin-home exec deploy/daikin-home -- cat /data/daikin-home.state.json > daikin-home.state.json.bak
```

Keep that file somewhere private: it contains the setup code in plain text.

To restore, scale to zero, write the file back, and scale up:

```bash
kubectl -n daikin-home scale deploy/daikin-home --replicas=0
# restore /data/daikin-home.state.json on the PVC, then:
kubectl -n daikin-home scale deploy/daikin-home --replicas=1
```

### Logs

```bash
kubectl -n daikin-home logs -f deploy/daikin-home
kubectl -n daikin-home logs deploy/daikin-home --previous   # after a restart
```

---

## Metrics and dashboards

This bridge has its own metrics stack, in this repository: `prometheus.yml`, and Grafana
provisioning plus a dashboard under `grafana/`, started by `compose.history.yaml`. It is
self-contained — its own config, its own storage, its own dashboard — so it runs with or
without the other bridges, and its Grafana shows this bridge only.

Under plain Docker:

```bash
docker compose -f compose.history.yaml up -d   # Prometheus and Grafana, without the bridge
```

That is Prometheus on <http://localhost:9090> and Grafana on <http://localhost:3000>. Set
`DAIKIN_PROMETHEUS_URL=http://localhost:9090` (or `"prometheusUrl"` in the config file) so
the bridge pushes to it. SpanHome's compose file serves the same pair on the same ports, so
if you run both bridges on one host, start only one of the two stacks and point both
bridges at it.

The bridge **pushes** OTLP metrics rather than being scraped, which is why Prometheus runs
with `--web.enable-otlp-receiver`. In Kubernetes, point `DAIKIN_PROMETHEUS_URL` at whatever
Prometheus you already run — an in-cluster one resolves by service name, because
`dnsPolicy: ClusterFirstWithHostNet` keeps cluster DNS working in a `hostNetwork` pod:

```bash
./deploy/k8s/create-secret.sh      # with "prometheusUrl" set in daikin.config.json
# or directly:
kubectl -n daikin-home set env deploy/daikin-home \
  DAIKIN_PROMETHEUS_URL=http://prometheus.monitoring.svc:9090
```

There are no Prometheus or Grafana manifests in `deploy/k8s` on purpose: most clusters
already run one, and installing a second would be worse than useless. The config files
above are the source of truth either way — load them into a ConfigMap if you do want a
dedicated pair in this namespace.

## Why the manifests look like this

**`replicas: 1` and `strategy: Recreate`.** Two pods would both bind the HomeKit port, both
advertise the same bridge over mDNS, and both write the same pairing-state file. A
`RollingUpdate` starts the new pod before the old exits, so it would fail to bind the port
and sit in `CrashLoopBackOff` while the old pod carried on — the update would never
complete.

**The probes avoid `/actuator/health`.** That endpoint includes the `daikinBridge`
indicator, which reports `DOWN` while the Daikin cloud is unreachable. A liveness probe on
it would restart the pod in a loop during a Daikin outage — a fault outside the cluster
that restarting cannot fix — and a readiness probe on it would pull the API out of service
exactly when you need it to tell you what is wrong. Both probes use Spring's
`liveness`/`readiness` groups instead, which cover the JVM and the HTTP listener.

**The service starts even when Daikin is down.** It comes up, reports `DOWN` on
`/actuator/health` with the reason, and retries in the background. Bad credentials are the
exception: those never fix themselves, so it stops retrying and health says
`retrying: false`.

**`dnsPolicy: ClusterFirstWithHostNet`.** Without it a `hostNetwork` pod inherits the
node's `resolv.conf` and loses cluster DNS, so it could not resolve an in-cluster
Prometheus by service name.

---

## When the mDNS responder dies

JmDNS can die under a running bridge — a network change on the node is enough — and when it
does it logs `Could not recover we are Down!` and never comes back. The bridge used to carry
on serving HTTP and reporting `UP` while being completely invisible to Apple Home.

It no longer does. The bridge owns its JmDNS instance and registers for the library's
`cannotRecoverFromIOError` callback, and separately probes every 60 seconds that multicast
to `224.0.0.251` still works at all (three consecutive failures count as dead, so a DHCP
renew does not trigger anything). When either fires:

- `/actuator/health` turns **DOWN** with `discoverable: false` and the reason.
- `/api/bridge` reports `discoverable: false` and `advertisementError`.
- The service publishes Spring's `LivenessState.BROKEN`, so
  **`/actuator/health/liveness` turns 503 and the pod is restarted.** This is what makes the
  liveness probe worth having; before this it could only catch a dead JVM.
- The CLI flavour exits 70 instead, so `Restart=on-failure` under systemd does the same job.

Readiness is deliberately left alone: the REST API still works and is the only way to read
the reason, so pulling the pod out of its Service would hide the diagnosis.

Recovery is a restart, not an in-process repair: JmDNS does not come back, so rebuilding it
underneath a live HAP server would be a lot of machinery for a fault whose honest answer is
"start again".

```bash
kubectl -n daikin-home logs deploy/daikin-home --previous | grep -i "advertisement is dead"
```

---

## Running the other bridges on the same node

The sibling bridges are built the same way and are designed to share a node — their ports
do not collide:

| Bridge | HTTP | HomeKit | Namespace |
| --- | --- | --- | --- |
| DaikinHome | 8080 | 51826 | `daikin-home` |
| CellGateHome | 8090 | 51829 | `cellgate-home` |
| SpanHome | 8089 | 51828 | `span-home` |

Give them all the same `homekit=true` node label. Each is a separate bridge in the Home app
with its own setup code and its own pairing state.

---

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| `Pod ... violates PodSecurity "restricted"` | The namespace does not allow `hostNetwork`. Apply `namespace.yaml`, which sets `enforce: privileged`. |
| Pod `Pending`, `0/n nodes are available`, `node(s) didn't match selector` | No node has `homekit=true`. |
| `ImagePullBackOff` with `denied` | The GHCR package is private. Make it public or add the pull secret (Step 1). |
| `ImagePullBackOff` with `no match for platform` | The image was not built for the node's architecture. Rebuild with `--platform linux/arm64`. |
| Pod `Pending` on the volume | No default `StorageClass`, or a node-local volume bound to a different node. `kubectl -n daikin-home describe pvc daikin-home-state`. |
| Bridge never appears in the Home app | mDNS is not reaching the phone. Confirm the node is on the same subnet, not a guest VLAN. |
| `Daikin API rejected the credentials` | Wrong API key/email/token, or a token generated on an account that does not own the thermostat. Verify with `--probe` from the main README. |
| Accessories say "No Response", health is `UP` | A stale pairing state, or the node moved. Check `discoverable` and the logs. |
| Accessories say "No Response", health is `DOWN` with `discoverable: false` | The mDNS responder died. The pod should restart itself; see the section above. |
| `Daikin API rate limited this integration` | Something polls too fast. The limit is per API key — check nothing else uses the same one. |
| Everything healthy, no thermostats | The integrator token belongs to an account that does not own the thermostat. |
