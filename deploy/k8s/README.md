# Running the bridge in Kubernetes

```bash
kubectl apply -k deploy/k8s                 # namespace, PVC, deployment, service
kubectl -n daikin-home create secret generic daikin-home-credentials \
  --from-literal=DAIKIN_BACKEND=skyport \
  --from-literal=DAIKIN_EMAIL='you@example.com' \
  --from-literal=DAIKIN_PASSWORD='...'
kubectl label node <node-name> homekit=true # the node on your home LAN
kubectl -n daikin-home logs -f deploy/daikin-home   # the setup code is printed here
```

## Read this before you deploy

**HomeKit does not work on a normal pod network.** Discovery and resolution are mDNS —
multicast to `224.0.0.251:5353` on the local link. A pod on a CNI overlay can neither send
nor receive that. The Home app would never find the bridge, and a bridge that was already
paired would stop resolving and show every accessory as "No Response".

That is why `deployment.yaml` sets `hostNetwork: true`. It is not a convenience; without it
(or a macvlan/ipvlan CNI) this cannot work. Three consequences follow:

1. **The pod must be scheduled on a node that is physically on the same LAN/VLAN as your
   Apple TV or HomePod.** mDNS does not cross subnets. Hence the `homekit=true`
   `nodeSelector` — set that label on the right node and nowhere else.
2. **The container's ports are the node's ports.** Nothing else on that node may bind
   `8080` or `51826`. If something does, change `server.port` / `daikin.homekit-port` via
   the Secret rather than fighting over it.
3. **A cloud cluster cannot host this at all.** If your control plane and nodes are in a
   datacentre, there is no LAN to multicast onto. This needs a node at home — a Pi, a NAS,
   a mini PC in a k3s cluster.

**Back up the PVC.** `daikin-home-state` holds the bridge's HomeKit identity and every
paired controller's key. Delete it and you re-pair from scratch in the Home app.

## Reaching the REST API

Two ways, and you do not need the second:

- **Directly on the node.** Because of `hostNetwork`, `http://<node-ip>:8080/api/thermostats`
  already works. This is the simplest thing that can possibly work and needs nothing else.
- **Through an Ingress.** `ingress.yaml` gives it a hostname (`daikin.home.arpa` by
  default). Edit the host and `ingressClassName` to match your controller, or delete the
  file from `kustomization.yaml` if you do not run one.

In-cluster, the Service is at `http://daikin-home.daikin-home.svc:8080`.

Note that this bridge currently serves a **REST API and actuator endpoints, not an HTML
dashboard** — `/api/thermostats`, `/api/bridge`, `/actuator/health`. There is no page to
look at yet.

**There is no authentication on any of it.** Anyone who reaches port 8080 can turn your
heating off. Keep it on your LAN; do not put it behind a public ingress. The Ingress
template has commented-out basic-auth annotations if you want a password in front of it.

## Why one replica, and why Recreate

`replicas: 1` with `strategy: Recreate`. Two pods would both bind the HomeKit port, both
advertise the same bridge over mDNS, and both write the same pairing-state file. A
`RollingUpdate` starts the new pod before the old one exits, so it would fail to bind the
port and sit in `CrashLoopBackOff` while the old pod carried on — the update would never
complete. `Recreate` takes a few seconds of downtime instead, which for a thermostat
bridge costs nothing.

## Why the probes avoid /actuator/health

`/actuator/health` includes the `daikinBridge` indicator, which reports `DOWN` while the
Daikin cloud is unreachable. Pointing a **liveness** probe at it would restart the pod in a
loop during a Daikin outage — a fault outside the cluster that restarting cannot fix.
Pointing a **readiness** probe at it would pull the API out of service exactly when you
need it to tell you what is wrong.

So both probes use Spring's `liveness`/`readiness` groups, which cover the JVM and the HTTP
listener. `/actuator/health` is still the right thing for you or Prometheus to watch.

## Pairing

The setup code is printed once at startup, and only while the bridge is unpaired:

```bash
kubectl -n daikin-home logs deploy/daikin-home | grep -A2 'setup code'
```

It is also on `GET /api/bridge` until the first controller pairs, after which it is
withheld. If you lose it, delete the state PVC and restart — you will re-pair everything.

## When the mDNS responder dies

JmDNS can die under a running bridge — a network change on the node is enough — and when
it does it logs `Could not recover we are Down!` and never comes back. The bridge used to
carry on serving HTTP and reporting `UP` while being completely invisible to Apple Home.

It no longer does. The bridge owns its JmDNS instance and registers for the library's
`cannotRecoverFromIOError` callback, and separately probes every 60 seconds that multicast
to `224.0.0.251` still works at all (three consecutive failures count as dead, so a DHCP
renew does not trigger anything). When either fires:

- `/actuator/health` turns **DOWN** with `discoverable: false` and the reason.
- `/api/bridge` reports `discoverable: false` and `advertisementError`.
- The service publishes Spring's `LivenessState.BROKEN`, so
  **`/actuator/health/liveness` turns 503 and the pod is restarted.** This is what makes
  the liveness probe worth having; before this it could only catch a dead JVM.
- The CLI flavour exits 70 instead, so `Restart=on-failure` under systemd does the same job.

Readiness is deliberately left alone: the REST API still works and is the only way to read
the reason, so pulling the pod out of its Service would hide the diagnosis.

Recovery is a restart, not an in-process repair: JmDNS does not come back, so rebuilding it
underneath a live HAP server would be a lot of machinery for a fault whose honest answer is
"start again".

```bash
kubectl -n daikin-home logs deploy/daikin-home --previous | grep -i "advertisement is dead"
```
