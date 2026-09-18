# Chess Database

The database stores games, metadata and position statistics in SQLite. Every game is keyed by its Scharnagl starting-position id so identical move prefixes from different Chess960 starts remain distinct.

## Position 518 compatibility

The application protocol now treats Scharnagl position 518 exactly like every other Chess960 position: external UCI castling uses king-to-rook-source notation. Existing database move codec v1 files, however, stored position-518 castling as classical king-to-final-square coordinates. `LegacyDatabaseMoveAdapter` is the single compatibility boundary between those two representations.

New imports, lookups and reconstructed games therefore expose the unified protocol representation while existing move bytes and Zobrist position statistics remain readable. No engine, PGN, replay or API code should reproduce this storage-only compatibility rule.

## Build

This module is built as part of the parent `chess-project` Maven reactor with Java 21.
