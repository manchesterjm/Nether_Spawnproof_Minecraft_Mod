# SpawnProof

A Minecraft Fabric mod that places buttons on all spawnable surfaces within a radius to prevent mob spawning. Perfect for Wither skeleton farms in the Nether.

[![Modrinth](https://img.shields.io/modrinth/dt/nether-spawnproof?logo=modrinth&label=Modrinth)](https://modrinth.com/mod/nether-spawnproof)
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-green)
![Fabric](https://img.shields.io/badge/Mod%20Loader-Fabric-blue)
![License](https://img.shields.io/badge/License-MIT-yellow)

## Features

- **Preview before placing** - See exactly how many buttons are needed with stack breakdown
- **Two speed modes** - Safe (10/sec) or Fast (200/sec)
- **Survival & OP modes** - Uses inventory in survival, unlimited in creative/OP
- **All button types** - Supports stone, wood, and Nether wood buttons
- **Configurable radius** - 8 to 128 blocks

## Commands

| Command | Description |
|---------|-------------|
| `/spawnproof` | Preview with default radius (128 blocks) |
| `/spawnproof <radius>` | Preview with custom radius (8-128 blocks) |
| `/spawnproof stop` | Stop the current task |
| `/spawnproof help` | Show all commands |

## Speed Modes

| Mode | Rate | Use Case |
|------|------|----------|
| **Safe** (default) | 10 buttons/sec | Conservative, minimal server impact |
| **Fast** | 200 buttons/sec | Quick coverage, tested with no TPS lag |

Time estimates for 88,000 buttons:
- Safe mode: ~2.5 hours
- Fast mode: ~7.5 minutes

## How It Works

### Preview System
Before placing any buttons, the mod scans the area and shows:
- **Spawnable blocks found** - Actual count of surfaces needing buttons
- **Buttons in inventory** (survival mode)
- **Time estimates** for both speed modes

Click **[START]** for safe mode or **[START FAST]** for fast mode.

### Game Modes

| Mode | Who | Behavior |
|------|-----|----------|
| **OP Mode** | Players with permission level 2+ | Unlimited stone buttons |
| **Survival Mode** | Regular players | Uses any button type from inventory |

### Supported Button Types (Survival Mode)
- **Stone, Polished Blackstone** - Don't burn
- **Warped, Crimson** - Nether wood, don't burn in the Nether!
- **Oak, Spruce, Birch, Jungle, Acacia, Dark Oak, Mangrove, Cherry, Bamboo**

### Spawnable Block Detection
A block is considered spawnable if:
- Current position is air (where the button will go)
- Block below is solid with full top surface (not bedrock)
- Block above is air (mob headroom)

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 1.21.11
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Download the latest release from [Releases](https://github.com/manchesterjm/Nether_Spawnproof_Minecraft_Mod/releases)
4. Place the `.jar` file in your `mods` folder

## Building from Source

Requires Java 21 and Gradle.

```bash
git clone https://github.com/manchesterjm/Nether_Spawnproof_Minecraft_Mod.git
cd Nether_Spawnproof_Minecraft_Mod
./gradlew build
```

The built jar will be in `build/libs/`.

## Dependencies

- Minecraft 1.21.11
- Fabric Loader 0.18.4+
- Fabric API 0.140.2+

## License

MIT License - see [LICENSE](LICENSE) for details.
