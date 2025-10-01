# JustBackup

An ultra-fast, space-efficient backup mod written by iceBear67.

## Features
 - Designed with speed in mind.  
    JustBackupMod utilizies Zstd compression algorithm, which is way faster(80% faster) than algorithms used in our competitors without sacrifising compression rate. (even smaller)
 - Chunk reassembler (Optional Feature)  
    JustBackupMod supports reassembling your chunks to a more space-efficient form. In detail, it tries to recompress your region files using Zstd for better compression rate, while empty areas are also stripped for smaller bundle sizes.
 - Incremental.  
   While JustBackupMod is already fast at full backups, you can enable incremental backups to gain more speed. By default, JustBackupMod perform a full backup on server startup and keep tracking for files modified and needed to make a backup.
 - S3 Support.  
   JustBackupMod also supports uploading your backups to S3 to release spaces for your minecraft world.

## Roadmap
- [x] Implement all features
- [ ] Further testing and a serious benchmark.
- [ ] Publish to modrinth
   