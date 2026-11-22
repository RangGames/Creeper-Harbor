package wiki.creeper.harbor.collection.api;

import wiki.creeper.harbor.common.id.FishId;

/** One server record broken this week (billboard "new records this week" page). */
public record WeeklyRecord(FishId fish, RecordEntry entry) {}
