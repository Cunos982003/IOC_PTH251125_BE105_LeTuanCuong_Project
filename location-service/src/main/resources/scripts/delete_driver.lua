-- Atomic delete driver from all keys
-- KEYS[1] = loc:geo, KEYS[2] = loc:lastseen, KEYS[3] = loc:busy
-- ARGV[1] = driverId, ARGV[2] = histKeyPrefix

local geoKey = KEYS[1]
local lastSeenKey = KEYS[2]
local busyKey = KEYS[3]
local driverId = ARGV[1]
local histKeyPrefix = ARGV[2]

redis.call('ZREM', geoKey, driverId)
redis.call('ZREM', lastSeenKey, driverId)
redis.call('HDEL', busyKey, driverId)
redis.call('DEL', histKeyPrefix .. driverId)

return 1