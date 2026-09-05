// Runs once, against mongo1, as root. Initiates the set and creates the two
// non-root users: one for the application, one for telemetry.
try {
  rs.status();
  print("replica set already initiated");
} catch (e) {
  print("initiating rs0");
  rs.initiate({
    _id: "rs0",
    members: [
      { _id: 0, host: "mongo1:27017", priority: 2 },
      { _id: 1, host: "mongo2:27017", priority: 1 },
      { _id: 2, host: "mongo3:27017", priority: 1 },
    ],
  });
}

// rs.initiate returns before the election finishes.
while (!db.hello().isWritablePrimary) {
  print("waiting for primary...");
  sleep(500);
}
print("primary: " + db.hello().me);

function ensureUser(user, pwd, roles) {
  try {
    db.getSiblingDB("admin").createUser({ user: user, pwd: pwd, roles: roles });
    print("created user " + user);
  } catch (e) {
    if (e.codeName !== "Location51003" && e.code !== 51003) print("user " + user + ": " + e.codeName);
  }
}

// The telemetry user. clusterMonitor is what serverStatus and replSetGetStatus
// need; read on `local` is what the oplog window needs and clusterMonitor does
// not grant. Both the mongodb receiver and the Percona exporter use this.
ensureUser("otel", "otelpw", [
  { role: "clusterMonitor", db: "admin" },
  { role: "read", db: "local" },
]);

// The application. readWrite on one database, nothing else.
ensureUser("app", "apppw", [{ role: "readWrite", db: "shop" }]);
