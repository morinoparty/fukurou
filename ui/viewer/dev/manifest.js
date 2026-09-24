// Sample data for `pnpm dev` (served from ui/viewer/dev/). Not part of the build.
window.__FUKUROU_MANIFEST__ = {
  "schemaVersion": 1,
  "generator": {
    "name": "fukurou-ui",
    "version": "0.1.0"
  },
  "generatedAt": "2026-09-24T03:10:00Z",
  "title": "fukurou dev sample",
  "ci": {
    "repository": "morinoparty/fukurou",
    "sha": "0123456789abcdef0123456789abcdef01234567",
    "runId": "123",
    "runUrl": "https://github.com/morinoparty/fukurou/actions/runs/123"
  },
  "summary": {
    "total": 4,
    "passed": 2,
    "failed": 1,
    "error": 1
  },
  "players": [
    "Alice",
    "Bob"
  ],
  "shots": [
    "spawn",
    "stamp-thinking-face"
  ],
  "runs": [
    {
      "id": "paper-1.20.6",
      "artifact": "fukurou-paper-1.20.6",
      "base": "runs/paper-1.20.6/",
      "status": "passed",
      "result": {
        "schemaVersion": 1,
        "id": "paper-1.20.6",
        "status": "passed",
        "fukurou": {
          "version": "0.1.0",
          "portablemc": "5.0.4"
        },
        "minecraft": {
          "version": "1.20.6",
          "server": "paper",
          "build": 130,
          "channel": "STABLE"
        },
        "java": {
          "server": 21
        },
        "scenario": {
          "name": "sample",
          "source": "file:examples/sample.yml",
          "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
        },
        "plugins": [
          {
            "file": "Example-all.jar",
            "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "name": "Example",
            "version": "1.0",
            "role": "under-test",
            "source": null,
            "classFileMajor": 65,
            "enabled": true
          },
          {
            "file": "ProtocolLib.jar",
            "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "name": "ProtocolLib",
            "version": "5.4.0",
            "role": "dependency",
            "source": "github:dmulloy2/ProtocolLib@5.4.0/ProtocolLib.jar",
            "classFileMajor": 61,
            "enabled": true
          }
        ],
        "players": [
          {
            "name": "Alice",
            "op": true,
            "joined": true
          },
          {
            "name": "Bob",
            "op": false,
            "joined": true
          }
        ],
        "steps": [
          {
            "index": 0,
            "on": "server",
            "action": "command",
            "label": "time set noon",
            "status": "passed",
            "durationMs": 15,
            "error": null,
            "screenshot": null
          },
          {
            "index": 1,
            "on": "Alice",
            "action": "screenshot",
            "label": "spawn",
            "status": "passed",
            "durationMs": 800,
            "error": null,
            "screenshot": "screenshots/Alice/spawn.png"
          },
          {
            "index": 2,
            "on": "Bob",
            "action": "screenshot",
            "label": "spawn",
            "status": "passed",
            "durationMs": 800,
            "error": null,
            "screenshot": "screenshots/Bob/spawn.png"
          },
          {
            "index": 3,
            "on": "Alice",
            "action": "chat",
            "label": ":thinking_face:",
            "status": "passed",
            "durationMs": 15,
            "error": null,
            "screenshot": null
          },
          {
            "index": 4,
            "on": "Alice",
            "action": "screenshot",
            "label": "stamp-thinking-face",
            "status": "passed",
            "durationMs": 800,
            "error": null,
            "screenshot": "screenshots/Alice/stamp-thinking-face.png"
          },
          {
            "index": 5,
            "on": "Bob",
            "action": "screenshot",
            "label": "stamp-thinking-face",
            "status": "passed",
            "durationMs": 800,
            "error": null,
            "screenshot": "screenshots/Bob/stamp-thinking-face.png"
          }
        ],
        "failure": null,
        "screenshots": [
          {
            "player": "Alice",
            "name": "spawn",
            "path": "screenshots/Alice/spawn.png",
            "width": 160,
            "height": 90,
            "stepIndex": 1
          },
          {
            "player": "Bob",
            "name": "spawn",
            "path": "screenshots/Bob/spawn.png",
            "width": 160,
            "height": 90,
            "stepIndex": 2
          },
          {
            "player": "Alice",
            "name": "stamp-thinking-face",
            "path": "screenshots/Alice/stamp-thinking-face.png",
            "width": 160,
            "height": 90,
            "stepIndex": 4
          },
          {
            "player": "Bob",
            "name": "stamp-thinking-face",
            "path": "screenshots/Bob/stamp-thinking-face.png",
            "width": 160,
            "height": 90,
            "stepIndex": 5
          }
        ],
        "logs": [
          {
            "kind": "harness",
            "path": "logs/harness.log",
            "player": null
          },
          {
            "kind": "server",
            "path": "logs/server.log",
            "player": null
          },
          {
            "kind": "client",
            "path": "logs/clients/Alice.log",
            "player": "Alice"
          },
          {
            "kind": "client",
            "path": "logs/clients/Bob.log",
            "player": "Bob"
          }
        ],
        "startedAt": "2026-09-24T03:00:00Z",
        "finishedAt": "2026-09-24T03:05:12Z",
        "durationMs": 312000,
        "ci": null
      }
    },
    {
      "id": "paper-1.21.4",
      "artifact": "fukurou-paper-1.21.4",
      "base": "runs/paper-1.21.4/",
      "status": "failed",
      "result": {
        "schemaVersion": 1,
        "id": "paper-1.21.4",
        "status": "failed",
        "fukurou": {
          "version": "0.1.0",
          "portablemc": "5.0.4"
        },
        "minecraft": {
          "version": "1.21.4",
          "server": "paper",
          "build": 130,
          "channel": "STABLE"
        },
        "java": {
          "server": 21
        },
        "scenario": {
          "name": "sample",
          "source": "file:examples/sample.yml",
          "sha256": "0000000000000000000000000000000000000000000000000000000000000000"
        },
        "plugins": [
          {
            "file": "Example-all.jar",
            "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "name": "Example",
            "version": "1.0",
            "role": "under-test",
            "source": null,
            "classFileMajor": 65,
            "enabled": true
          },
          {
            "file": "ProtocolLib.jar",
            "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "name": "ProtocolLib",
            "version": "5.4.0",
            "role": "dependency",
            "source": "github:dmulloy2/ProtocolLib@5.4.0/ProtocolLib.jar",
            "classFileMajor": 61,
            "enabled": true
          }
        ],
        "players": [
          {
            "name": "Alice",
            "op": true,
            "joined": true
          },
          {
            "name": "Bob",
            "op": false,
            "joined": true
          }
        ],
        "steps": [
          {
            "index": 0,
            "on": "server",
            "action": "command",
            "label": "time set noon",
            "status": "passed",
            "durationMs": 15,
            "error": null,
            "screenshot": null
          },
          {
            "index": 1,
            "on": "Alice",
            "action": "screenshot",
            "label": "spawn",
            "status": "passed",
            "durationMs": 800,
            "error": null,
            "screenshot": "screenshots/Alice/spawn.png"
          },
          {
            "index": 2,
            "on": "Bob",
            "action": "screenshot",
            "label": "spawn",
            "status": "passed",
            "durationMs": 800,
            "error": null,
            "screenshot": "screenshots/Bob/spawn.png"
          },
          {
            "index": 3,
            "on": "Alice",
            "action": "chat",
            "label": ":thinking_face:",
            "status": "failed",
            "durationMs": 30000,
            "error": "Timed out after 30s waiting for the chat message to appear.",
            "screenshot": null
          },
          {
            "index": 4,
            "on": "Alice",
            "action": "screenshot",
            "label": "stamp-thinking-face",
            "status": "skipped",
            "durationMs": null,
            "error": null,
            "screenshot": null
          },
          {
            "index": 5,
            "on": "Bob",
            "action": "screenshot",
            "label": "stamp-thinking-face",
            "status": "skipped",
            "durationMs": null,
            "error": null,
            "screenshot": null
          }
        ],
        "failure": {
          "phase": "scenario",
          "message": "Timed out after 30s waiting for the chat message to appear.",
          "stepIndex": 3
        },
        "screenshots": [
          {
            "player": "Alice",
            "name": "spawn",
            "path": "screenshots/Alice/spawn.png",
            "width": 160,
            "height": 90,
            "stepIndex": 1
          },
          {
            "player": "Bob",
            "name": "spawn",
            "path": "screenshots/Bob/spawn.png",
            "width": 160,
            "height": 90,
            "stepIndex": 2
          },
          {
            "player": "Alice",
            "name": "failure",
            "path": "screenshots/Alice/failure.png",
            "width": 160,
            "height": 90,
            "stepIndex": 3
          }
        ],
        "logs": [
          {
            "kind": "harness",
            "path": "logs/harness.log",
            "player": null
          },
          {
            "kind": "server",
            "path": "logs/server.log",
            "player": null
          },
          {
            "kind": "client",
            "path": "logs/clients/Alice.log",
            "player": "Alice"
          },
          {
            "kind": "client",
            "path": "logs/clients/Bob.log",
            "player": "Bob"
          }
        ],
        "startedAt": "2026-09-24T03:00:00Z",
        "finishedAt": "2026-09-24T03:05:12Z",
        "durationMs": 312000,
        "ci": null
      }
    },
    {
      "id": "paper-1.21.7",
      "artifact": "fukurou-paper-1.21.7",
      "base": "runs/paper-1.21.7/",
      "status": "error",
      "result": null
    },
    {
      "id": "paper-1.21.11",
      "artifact": "fukurou-paper-1.21.11",
      "base": "runs/paper-1.21.11/",
      "status": "passed",
      "result": {
        "schemaVersion": 2,
        "id": "paper-1.21.11",
        "status": "passed"
      }
    }
  ],
  "warnings": [
    "fukurou-paper-1.21.7: result.json missing (job cancelled?)"
  ]
};
