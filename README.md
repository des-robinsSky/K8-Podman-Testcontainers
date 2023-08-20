# Spring Boot Testcontainers PoC housed in Kubernetes and using Rootless Podman and its REST interface

## PoC constraints/caveats

1. Security/Core mandated the use of the VFS storage driver. VFS is known to be less performant.
   See: https://docs.docker.com/storage/storagedriver/vfs-driver/
2. Podman has provided an overlay solution but this is not currently approved for use.
   See: https://www.redhat.com/sysadmin/podman-rootless-overlay
3. Kubernetes pods MUST run with `privileged=false`. This eliminates Docker as the `dind-rootless` solution currently
   requires this to be `true`.
4. Kubernetes pods must run in Rootless/non-root user mode.
5. A sample `podman-pod.yml` file has been supplied working within these restrictions.

## Gotchas

1. Rootless mode defaults to the host driver, which means child container ports are exposed on the host.
2. Thus, if the container is configured to expose port 9042, then this port must be free on the host. If
   parallel testing is required, additional work will be required to create containers supporting dynamic port
   allocation.
3. Other alternatives exist, but were not explored for this PoC.
   See: https://www.redhat.com/sysadmin/container-networking-podman.
   Most examples assume Podman is running on a VM or server which means the example link should be used as a mental
   model of the behaviour but any solution would require work to make it fit in the Kubernetes/Pod model. Other 
sources also mention changing from 'slirp4netns' etc. so there are many options to try.
4. The basis for this PoC came from https://www.redhat.com/sysadmin/podman-inside-kubernetes, but that is
   light on networking information.
5. Testcontainers uses the Docker API to manage containers which the Podman team have worked hard to support
   using their REST API (compat) mode. See: https://docs.podman.io/en/latest/_static/api.html
6. A rather irksome issue led to the PoC code block below. The Testcontainer tests do not have any host only
   tests that use ports. As a result, I suspect there is an undiscovered bug which the PoC hit. In short, the
   documentation explicitly mentions NOT using the `exposedPorts` mechanism because there are no ports to expose
   with host networking. Unfortunately there is a 5 second timeout in the `GenericContainer` class that tests the
   ports that are bound to the application against the exposed ports and if a match is not found, will throw an
   exception. To avoid the problem, the two step approach below is required. The first line sets the port bindings
   which unfortunately also sets the exposed ports list internally, which leads to the second line which clears
   the list.
   See: [host test](https://github.com/testcontainers/testcontainers-java/blob/7b69ca0fdc71c12a9ec6f23eff7ab6fedc19388d/core/src/test/java/org/testcontainers/junit/DockerNetworkModeTest.java#L36),
   [cassandra tests](https://github.com/testcontainers/testcontainers-java/blob/7b69ca0fdc71c12a9ec6f23eff7ab6fedc19388d/modules/cassandra/src/test/java/org/testcontainers/containers/CassandraContainerTest.java#L15)

```java
cassandraContainer.setPortBindings(List.of("9042/tcp"));
cassandraContainer.setExposedPorts(List.of());
```

7. Due to the issue raised in point 6 above, the PoC uses the Wait/StartStrategy mechanisms in the code block
   below as the 'normal' Cassandra specific versions don't work e.g. `CassandraQueryWaitStrategy()`.
8. More reading: https://github.com/containers/podman/blob/main/rootless.md

```java
.withStartupCheckStrategy(new IsRunningStartupCheckStrategy())
.waitingFor(new LogMessageWaitStrategy().withRegEx(".*Startup complete.*"));
```

## PoC details

The example used is a Spring Boot application that spins up a Cassandra container and then runs a simple
query once the container is running. As a result of the networking issues above, the app is configured using
the Spring Profile mechanism to enable/disable host mode networking. Host mode could be used locally if 
the OS is Linux (not macOS or Windows). See: https://docs.docker.com/network/drivers/host/

### Checking Podman is working in your Kubernetes Pod

**NOTE:** You can alias `podman` to `docker` if you want. The Podman commands are Docker compatible.

- The first command to try from the CLI in the K8 pod, is `podman info`.
- To see if you can pull images, use: `podman pull <image:version>`. With the current image, this sometimes
  fails as I had not defined a required folder - remedied using: `mkdir -p /var/tmp`
- To run a container use something like: `podman run -it --rm --name container_name -v /proc:/proc image_name /bin/sh`

### Files of note:

- `Dockerfile`: The PoC assumes an image is created from this file and pushed to an image registry.
- `podman-pod.yml`: using `kubectl apply -f podman-pod.yml` you can start a pod using the image created
  above. Note the `DOCKER_HOST` value and the command to activate the REST api
  using `podman system service tcp:localhost:9111 -t 0 &`.
  More detail can be found here: https://docs.podman.io/en/latest/markdown/podman-system-service.1.html
- `TestK8PodmanPinPApplication` shows the configuration for the Profile settings.

## Future improvements

### High value

- Eliminate the two tier approach of this PoC to use a shared networking model for local and in Kubernetes containers. 
See the Podman network docs: https://github.com/containers/podman/blob/main/docs/tutorials/basic_networking.md
- Support dynamic port allocation. The Bridge network does this by forwarding a random host port to the fixed internal
  container port. A potential host mode solution could define dynamic images (
  see: https://java.testcontainers.org/features/creating_images/)
  or possibly extending on existing ideas e.g. https://www.baeldung.com/spring-dynamicpropertysource
- One of the issues on the Podman Github repo discusses disabling the default host only networking for Rootless
  containers and a search there may yield some ideas for better networking solutions. 
Also see: https://www.redhat.com/sysadmin/container-networking-podman
- Docker/Podman compose files. These are supported (in Testcontainers) but need a network solution.  
  See: https://java.testcontainers.org/modules/docker_compose/
- If a dynamic host only solution is pursued, it would be useful to adapt the standard `CassandraQueryWaitStrategy()`
  to support host networking.

### Erm... less value?

- Tidy up `podman-pod.yml` to consolidate the three empty volumes. Also remove redundant `env` settings.
- Remove the current need to run `mkdir -p /var/tmp` before pulling images will work in the K8 Pod.

## Useful...?

As mentioned before, Testcontainers uses the Docker API to manage containers. While fighting with this
PoC, the point where containers were running via the CLI was reached quite quickly. The point where the
Tests actually did the same was another story. Along the way, the following commands helped to understand
what was going on. The commands below assume `curl` is installed in the container. All API commands
are those listed above in the Podman REST API link, specifically the 'compat' ones which support Docker
calls.

- List running containers: `curl localhost:9111/containers/json`
- Creating and starting containers is a two-step process. The commands below assume you have created a file named
  `create.json` (and example is provided in the PoC root - per the 'gotcha' regarding 'exposed ports' note the
  list is empty in the example) and want to create a container named `cassy`:
    - `curl -H "Content-Type: application/json" -d @create.json -X POST localhost:9111/containers/create?name=cassy`
    - `curl -H "Content-Type: application/json" -X POST localhost:9111/containers/cassy/start`
- Opening a terminal in the (hopefully) running container: `podman exec --tty -it cassy`
- To stop a container replace `start` in the second command with `stop`. This does NOT remove the container
  though (same as the CLI when you don't use `--rm`).

How did I find the json above... Testcontainers will log all the HTTP calls it makes to the Docker API if
you simply add `--debug` to the gradle command e.g. `./gradlew --debug test`. I couldn't understand why
the containers I ran were starting fine from the CLI but Testcontainers kept telling me they failed. As
I already knew the containers ran using the CLI, I did the same but using `curl`. By comparing the json
I used with `curl` (once it started correctly) to the json created by Testcontainers, I could narrow down
the sections of the json that differed.  