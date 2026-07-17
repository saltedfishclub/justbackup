# JustBackup

A fast, space-efficient backup mod for Fabric servers, written by iceBear67.

- **Minecraft 26.2** · Fabric Loader ≥ 0.19.3 · Java 25
- Consistent backups: no torn region files, even while the server is running
- Incremental backups with **chained restore** (a full base + its incrementals restore as one unit)
- **Reflink (CoW) fast path**: on btrfs/XFS/ZFS the world is only "frozen" for milliseconds
- Local directory or **S3-compatible** storage, generation-based rotation
- Zstd compression with optional trained dictionaries and a chunk reassembler

## How it works

On each backup, JustBackup:

1. saves the world on the main thread and drains the chunk IO workers, so everything is complete on disk;
2. takes an exclusive lock over region IO (chunk writes park on a read-write lock — the main thread itself never blocks: autosaves and `/save-all` issued during a backup are skipped and logged);
3. snapshots the files — on CoW filesystems by reflink-cloning them into a staging directory (metadata-only, milliseconds, no extra disk space), then releases the lock immediately; without CoW support it keeps the lock while packing;
4. packs everything into a zstd-compressed bundle (`.swb.zst`) and hands it to the storage backend.

Changed files are tracked by a filesystem watcher, so incremental backups only bundle what actually changed. If the OS drops watch events (overflow, directory replaced), the next backup automatically upgrades itself to a full one — the mod never silently backs up less than you think.

### Reflink requirements

The fast path needs Linux and a filesystem supporting `FICLONE` (btrfs, XFS with reflink, OpenZFS ≥ 2.2), and `temporaryBackupDir` must be on the **same filesystem** as the backup subject. Anything else (ext4, overlayfs in containers, cross-mount setups) falls back to packing under the lock — correct, just with a longer pause of chunk IO. The mod logs which mode it uses.

## Configuration

`config/just_backup.json` (created with defaults on first run):

```json
{
  "bundleFullAtStartup": true,
  "incremental": true,
  "broadcast": true,
  "backupIntervalMinutes": 60,
  "keepBackups": 5,
  "compressionLevel": 3,
  "compressionWorkerThreads": 4,
  "allowGunzip": true,
  "temporaryBackupDir": "./.backup_cache",
  "zstdDictPath": null,
  "backupSubjects": { "world": "world" },
  "option": { "type": "local", "saveDir": "backups", "diskSizeQuotaBytes": 0 }
}
```

| Key | Meaning |
|---|---|
| `bundleFullAtStartup` | Take a full backup when the server starts. Recommended: incrementals need a full base to restore. |
| `incremental` | Scheduled backups only bundle changed files. Full backups are still taken at startup and on demand. |
| `broadcast` | Announce backup progress/results to players (always logged to console). |
| `backupIntervalMinutes` | Scheduled backup interval. Skipped while the server is idle (no ticks advanced). |
| `keepBackups` | How many **generations** to keep per subject. A generation = one full backup plus all incrementals after it; old generations are deleted whole, so an incremental never loses its base. |
| `compressionLevel` | Zstd level (must be > 0). |
| `compressionWorkerThreads` | Zstd multithreading; `0`/`1` = single-threaded. |
| `allowGunzip` | Chunk reassembler: recompress region files as a whole for a better ratio (see below). |
| `temporaryBackupDir` | Scratch space for bundles and reflink staging. Put it on the same filesystem as your world to enable the CoW fast path. |
| `zstdDictPath` | Optional zstd dictionary trained with the bundler CLI. |
| `backupSubjects` | Named directory trees to back up, e.g. the world folder. `@all` is reserved. |
| `option` | Storage backend, see below. |

**Local storage** — `{ "type": "local", "saveDir": "backups", "diskSizeQuotaBytes": 0 }`. `diskSizeQuotaBytes` > 0 refuses new backups once the backup dir exceeds the quota (`0` = unlimited).

**S3 storage** —

```json
{
  "type": "s3",
  "region": "us-east-1",
  "accessKey": "...",
  "secretKey": "...",
  "endpoint": "https://s3.example.com",
  "bucket": "my-backups",
  "prefix": "myserver",
  "enforcePathStyle": true,
  "retryAmount": 3
}
```

Uploads retry with exponential backoff up to `retryAmount` times.

## Commands

All commands require permission level 4 (owners).

| Command | Effect |
|---|---|
| `/backup help` | Show help. |
| `/backup list` | List tracked backups with `FULL`/`INCR`, size and creation time, plus clickable delete/restore shortcuts. |
| `/backup create <incremental> <subject\|@all>` | Take a backup now. |
| `/backup restore <backupKey>` | Schedule a restore for the next shutdown. Restoring an **incremental** automatically applies its full base and every incremental in between, in order — the resolved chain is printed for confirmation. |
| `/backup cancelrestore` | Cancel a scheduled restore. |
| `/backup delete <backupKey>` | Delete one backup, no confirmation. |
| `/backup suspend` | Toggle the automatic backup scheduler until next restart. |

### How restore works

The restore runs during server shutdown: the current world directory is moved aside to `<world>_bak_<timestamp>` (nothing is deleted), then each bundle in the chain is extracted over the same destination, later bundles overwriting earlier ones. If anything fails midway, your original world is intact in the `_bak_` directory — move it back to undo.

## The chunk reassembler (`allowGunzip`)

Region files store each chunk individually zlib-compressed, which compresses poorly as a whole. With the reassembler enabled, backups decompress chunks and store them raw inside the bundle so zstd can compress across chunk boundaries (optionally with a trained dictionary); restores rebuild standard `.mca` files with zlib, byte-identical chunk data, vanilla-compatible layout. Region files that fail to parse are stored as-is instead of failing the backup.

## Bundler CLI (`jpack`)

The `bundler` subproject builds a standalone tool for working with `.swb.zst` bundles outside the server:

```console
# extract a bundle
$ java -jar bundler.jar --in backup_world_123.swb.zst --out ./restored_world

# pack a directory
$ java -jar bundler.jar --in ./world --out world.swb.zst --level 9

# train a zstd dictionary on your region files
$ java -jar bundler.jar --train ./world/dimensions --stat
$ java -jar bundler.jar --train ./world/dimensions --train-sample-size <n> --train-dict-size <n>
```

Run with `--help` for all options.

## Known limitations

- File deletions are not tracked: a chained restore may resurrect files deleted between two backups. Minecraft worlds rarely delete files, so in practice this is cosmetic.
- While a backup holds the IO lock (non-CoW fallback), chunk saves queue up in memory and autosaves are skipped; nothing is lost, but on huge worlds prefer a CoW filesystem to keep the pause short.
- Bundles created by pre-1.0 development builds contain incorrectly reassembled region data and cannot be restored. Take a fresh full backup after upgrading.

## Building

```console
$ JAVA_HOME=/path/to/jdk-25 ./gradlew build
```

The distributable mod jar (with dependencies shaded) is `build/libs/justbackup-<version>.jar`; the bundler CLI is under `bundler/build/libs/`. Run `./gradlew runServer` for a dev server in `run/`.

## License

CC0-1.0
