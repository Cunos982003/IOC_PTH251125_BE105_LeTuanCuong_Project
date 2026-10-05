-- Atomic cleanup of stale drivers
-- KEYS[1] = loc:geo, KEYS[2] = loc:lastseen, KEYS[3] = loc:busy
-- ARGV[1] = staleThreshold (timestamp), ARGV[2] = histKeyPrefix
-- Returns: number of drivers cleaned up

local geoKey = KEYS[1]
local lastSeenKey = KEYS[2]
local busyKey = KEYS[3]
local staleThreshold = tonumber(ARGV[1])
local histKeyPrefix = ARGV[2]

-- Get all stale drivers (score <= staleThreshold)
local staleDrivers = redis.call('ZRANGEBYSCORE', lastSeenKey, 0, staleThreshold)

if #staleDrivers == 0 then
    return 0
end

-- Remove from GEO (ZREM on sorted set)
redis.call('ZREM', geoKey, unpack(staleDrivers))
-- Remove from LASTSEEN
redis.call('ZREM', lastSeenKey, unpack(staleDrivers))
-- Remove from BUSY
redis.call('HDEL', busyKey, unpack(staleDrivers))
-- Delete history keys
for _, driverId in ipairs(staleDrivers) do
    redis.call('DEL', histKeyPrefix .. driverId)
end

return #staleDrivers