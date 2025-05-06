<br/>
<h1 align="center">Modpack Updater</h1>
<div align="center">
Modpack updater is a stack of applications which automatically update modpack's in Minecraft. It is broken down into a Fabric mod, a RestAPI, and a CLI tool.
<br/>
<br/>

![Downloads](https://img.shields.io/github/downloads/aderoian/ModpackUpdater/total) ![Discord](https://img.shields.io/discord/1369197390480801822)
</div>

# How It Works
1. A modpack developer or maintainer will use the CLI tool to create modpack updates using the CLI tool.
    1. The CLI tool uses the latest mods in the modpack's mod list to create a new modpack version.
    2. The CLI tool will generate a new modpack version and upload it to the update RestAPI.
2. When a player launches the modpack, it will compare its current version to the latest from the RestAPI.
    1. If outdated, the modpack will prompt the player to update.
    2. If the player chooses to update, the modpack will download the latest version from the RestAPI.
    3. Minecraft will close, the mods will be downloaded, and the user must relaunch Minecraft.

# DISCLAIMERS: 
## Security [IMPORTANT!!!]
There is a **HUGE** security vulnerability using this modpack updater if used maliciously. If you are using a public modpack using this tool you can be subjected to installing malicious code.
If you are using this tool, please use it at your own risk. I am not responsible for any damage done to your computer or Minecraft instance. Please do not use this tool if you are not comfortable with the risks involved.

I recommend this tool only be used in private modpacks between users of a trusted group, with users who understand its capabilities.

## Setup
This tool does not come ready to use. The RestAPI must be hosted on a server, and the CLI tool must be run to create modpack updates.

# Features
- Automatically update modpacks via prompt
- CLI tool to create modpack updates
- RestAPI to host modpack updates
- Fabric mod to check for updates
- Update menu to view update information and change logs
- Update channels (i.e. Stable, Beta, Alpha)
- Backup URL downloading for mods not hosted on Modrinth

# Setup
## Fabric Mod
As a modpack developer/maintainer you must ship the modpack with the 2 updater jars. `updateNotifier-{version}.jar` (the mod) and `updater-{version}.jar` (the CLI tool and update script). These files are vital, clients will not be able to update without them. The mod will check for updates and the CLI tool/script will preform the update.
## RestAPI
The RestAPI is a simple java application that serves as the backend for the modpack updater. It is responsible for hosting the modpack updates and providing the update information to the modpack. The RestAPI must be hosted on a server that is accessible to the modpack clients.

1. Download the RestAPI jar from the releases page.
2. Create a new directory and place the jar in it.
3. Start the jar with the following command:
```bash
java -jar updater-{version}.jar --port {port} --updates-dir "{updates-dir}" --api-key {api-key} --channels "channels"
```
- `port`: The port the RestAPI will run on. Default is 8080.
- `updates-dir`: The directory where the modpack updates will be stored. Default is `./updates`.
- `api-key`: The api key used to authenticate the CLI tool. This is a required field.
- `channels`: The channels the RestAPI will support. This is a required field. The channels must be in the format of a comma separated list. (i.e. "stable,beta,alpha")

4. (Optional) Create a reverse proxy to the RestAPI. This is recommended for security and performance reasons. The RestAPI will be accessible at `http://{server-ip}:{port}/api/` by default. If you are using a reverse proxy, the RestAPI will be accessible at `http://{server-ip}/`.

Example Nginx configuration:
```
server {
    listen 80;
    server_name {hostname};
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    server_name {hostname};

    ssl_certificate /etc/letsencrypt/live/{hostname}/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/{hostname}/privkey.pem;

    location / {
        proxy_pass http://localhost:{port}/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

# Usage
## Endpoints
### **GET** `/{channel}/latest?update={true|false}`
- `channel`: The channel to get the latest update from. This is a required field. The channel must be one of the channels specified in the RestAPI setup.
- `update`: If true, the endpoint will return the latest update including mods, else it will only return the latest update metadata.

Status Code: 200
```json5
{
  "code": 200,
  "data": {
    "meta": {
      "name": "Version Name",
      "description": "Version Description",
      "version": "Version Number",
      "releaseDate": "Date of Release"
    },
    "entries": [ // If update is true, this will be present
      {
        "type": "ADDED", // ADDED, REMOVED, CHANGED
        "id": "mod-id",
        "name": "Mod Name",
        "version": "Mod Version",
        "downloadUrl": "https://example.com/mod.jar",
        "fileName": "mod.jar"
      },
      //...
    ]
  }
}
```

Status Code: 404
```json5
{
  "code": 404,
  "message": "Latest update not found"
}
```

### **GET** `/{channel}/get/{version}`
- `channel`: The channel to get the update from. This is a required field. The channel must be one of the channels specified in the RestAPI setup.
- `version`: The version to get the update from. This is a required field. The version must be in the format of `major.minor.patch` (i.e. 1.0.0)

Status Code: 200
```json5
{
  "code": 200,
  "data": {
    "meta": {
      "name": "Version Name",
      "description": "Version Description",
      "version": "Version Number",
      "releaseDate": "Date of Release"
    },
    "entries": [
      {
        "type": "ADDED", // ADDED, REMOVED, CHANGED
        "id": "mod-id",
        "name": "Mod Name",
        "version": "Mod Version",
        "downloadUrl": "https://example.com/mod.jar",
        "fileName": "mod.jar"
      },
      //...
    ]
  }
}
```

Status Code: 404
```json5
{
  "code": 404,
  "message": "Update not found"
}
```

### **GET** `/{channel}/getFull/{version}`

This endpoint will return a merge of updates from one channel in the range from the given version to the latest version. This is useful for when a client may have missed an update and needs to get all the changes since the last version.

- `channel`: The channel to get the update from. This is a required field. The channel must be one of the channels specified in the RestAPI setup.
- `version`: The version to get the update from. This is a required field. The version must be in the format of `major.minor.patch` (i.e. 1.0.0)

Status Code: 200
```json5
{
  "code": 200,
  "data": {
    "meta": {
      "name": "Version Name",
      "description": "Version Description",
      "version": "Version Number",
      "releaseDate": "Date of Release"
    },
    "entries": [
      {
        "type": "ADDED", // ADDED, REMOVED, CHANGED
        "id": "mod-id",
        "name": "Mod Name",
        "version": "Mod Version",
        "downloadUrl": "https://example.com/mod.jar",
        "fileName": "mod.jar"
      },
      //...
    ]
  }
}
```

Status Code: 404
```json5
{
  "code": 404,
  "message": "Latest update not found"
}
```

### **POST** `/{channel}/create`
- `channel`: The channel to create the update in. This is a required field. The channel must be one of the channels specified in the RestAPI setup.

Headers:
- `X-API-Key`: The api key used to authenticate. This is a required field.

Body:
```json5
{
  "name": "Version Name",
  "description": "Version Description",
  "version": "Version Number",
  "releaseDate": "Date of Release",
  "entries": [
    {
      "type": "ADDED", // ADDED, REMOVED, CHANGED
      "id": "mod-id",
      "name": "Mod Name",
      "version": "Mod Version",
      "downloadUrl": "https://example.com/mod.jar",
      "fileName": "mod.jar"
    },
    //...
  ]
}
```

Status Code: 200
```json
{
  "code": 200,
  "message": "Update created"
}
```

Status Code: 400
```json
{
  "code": 400,
  "message": "Bad request"
}
```

Status Code: 403
```json
{
  "code": 403,
  "message": "Invalid API key"
}
```

## CLI Tool
The CLI tool is used to create modpack updates and upload them to the RestAPI. It is a simple command line tool that takes a modpack directory and uploads it to the RestAPI.
### Dump Command
```bash
java -jar updater-{version}.jar dump
```
This command will dump the modpack directory and show all the mods in the modpack. This is useful for debugging and checking the modpack directory.

### Create Command
```bash
java -jar updater-{version}.jar create [push]
```
- `push`: If this flag is present, the CLI tool will push the update to the RestAPI. If not present, the CLI tool will only create the update JSON and print it to the console. This is useful for debugging and checking the update before uploading.

This command has an interactive prompt that will ask for the following information:
- `name`: The name of the update. This is a required field.
- `description`: The description of the update. This is a required field.
- `version`: The version of the update. This is a required field. The version must be in the format of `major.minor.patch` (i.e. 1.0.0)
- `channel`: The channel to create the update in. This is a required field. The channel must be one of the channels specified in the RestAPI setup.
- `update url`: The url of the REST API. This is a required field. 
- `backup url`: The download url of mods not hosted on Modrinth. This is a required field.
- `api key`: The api key used to authenticate. This is a required field.

