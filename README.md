# Chess Database

`chess-database` provides the embedded local game library for the Chess Analysis Tool. It stores imported games in SQLite and builds position-level statistics that can be used while studying games and positions.

## Import format

Database sources must be supplied in **PGN (Portable Game Notation)** format. A PGN file may contain one game or a large multi-game collection. The importer reads games as a stream instead of loading the complete library into memory at once.

Bulk import tracks processed, imported, and skipped games as well as imported plies and byte progress. Imports are isolated: games and position statistics belonging to an import are not exposed to normal queries until the import completes successfully. Cancelling or failing an import removes its staged data.

Large public or personal PGN collections can be expensive to process because the importer parses games, validates/replays moves, stores game metadata, and aggregates position/move statistics. Depending on collection size, CPU, and storage performance, an import can take many minutes or several hours.

## Storage and search

The implementation uses SQLite. In the complete application the default database path is `~/.chess/database/chess.db`; the API layer can override it with the `chess.database.path` system property.

Stored games can be searched by player names, year range, result, and minimum Elo. A stored game can be reconstructed as PGN and loaded back into the normal analysis workflow. The database also exposes statistics for positions reached after a sequence of moves.

## Design

The module depends on the `chess` core for PGN/game processing and maintains its own compact move encoding and position hashing. Duplicate handling and position aggregation are performed inside the database layer so both single-game and bulk imports use the same persistence rules.

## Build

This module is a Maven JAR project targeting Java 17 and uses the Xerial SQLite JDBC driver. It is normally built as part of the parent `chess-project` Maven reactor.
