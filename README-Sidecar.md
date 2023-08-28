# Extension of the base PoC to include a sidecar approach

## This README is limited to specific info for the Sidecar solution. For more complete information, refer to the core README.

## TL;DR
1. Create a Sidecar image for use using `docker build --platform linux/amd64 -t <image registry>/pod-side:v??? -f Dockerfile.side . --push`
2. Create an App image for use using `docker build --platform linux/amd64 -t <image registry>/pod-app:v??? -f Dockerfile.app . --push`
3. Update `podman-sidecar.yml` populating the `namespace` and the TWO `image` fields (app and sidecar).
4. Create a K8 pod using `kubectl apply -f podman-sidecar.yml`
5. Open a terminal in the pod using `kubectl exec -it podman -c app -- sh`
6. Then run `./gradlew test`

## Sidecar build

The primary difference between the sidecar and base README builds is that the sidecar build isolates the Podman 
and App images (allowing reuse of the Podman image and avoiding the need to recreate it constantly). The 'normal' 
build described in the base README copies the app and installs Podman into the same image. With the 
Sidecar build, the app is housed in its own container which is completely unaware of the container creation tooling 
and simply uses Testcontainers to start containers via a TCP call.