# Gitea Auto Mirror

This is a simple script, that creates mirror repositories on a [Gitea](https://gitea.com) server for all 
accessible repositories from another Gitea server.

The main idea is to have a simpler backup of the most important stuff (the source code) readily available 
and usable without explicit restore actions.

This does not replace a real backup of the Gitea server and its underlying storage / database.

You should only use this script to automatically create mirror repos between two self-hosted Gitea instances.

## Installation / Running

It is a simple [Kotlin](https://kotlinlang.org) script using Giteas REST API.

You can run it directly as a script, if you have the [Kotlin Executable](https://github.com/Jetbrains/kotlin/releases/latest)
installed (also available via [SDKMAN!](https://sdkman.io)):

```shell
kotlin src/sync.main.kts
```

or directly

```shell
./src/sync.main.kts
```

Or you can run it in a container (based on [Guillermo Mazzolas kotlin-container](https://github.com/gmazzo/kotlin-container))

```shell
docker run --rm ghcr.io/norganos/gitea-auto-mirror/gitea-auto-mirror:latest \
      -e SOURCE_GITEA_URL="https://source-gitea.url" \
      -e SOURCE_GITEA_TOKEN="<api-token-with-read-permissions>" \
      -e TARGET_GITEA_URL="https://target-gitea.url" \
      -e TARGET_GITEA_TOKEN="<api-token-with-write-permissions>"
```

Of course it's intended to be run periodically (e.g. via cron or Kubernetes CronJob) 

## Configuration

The script is controlled by these environment variables:

- `SOURCE_GITEA_URL`: The URL (complete, with protocol, and root path) of the source Gitea server
- `SOURCE_GITEA_TOKEN`: An API Token with read permissions for source Gitea server
- `TARGET_GITEA_URL`: The URL (complete, with protocol, and root path) of the target Gitea server
- `TARGET_GITEA_TOKEN`: An API Token with organization and repository write permissions for target Gitea server
- `DRY_RUN`: "true" disables write access to target Gitea server (in that case you can set TARGET_GITEA_TOKEN to a token with read permissions only to be sure)
- `WHITE_LIST_ORGANIZATIONS`: A list of organization names (space separated). If set, only these organizations (and their repos) are synced 
- `BLACK_LIST_ORGANIZATIONS`: A list of organization names (space separated). If set, these organizations are skipped
- `WHITE_LIST_REPOSITORIES`: A list of repo full names (space separated, in the form "<org>/<repo>"). If set, only these repositories are synced
- `BLACK_LIST_REPOSITORIES`: A list of repo full names (space separated, in the form "<org>/<repo>"). If set, these repositories are skipped
