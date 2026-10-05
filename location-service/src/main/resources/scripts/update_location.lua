-- Atomic update location for non-busy driver
-- KEYS[1] = loc:geo, KEYS[2] = loc:lastseen, KEYS[3] = loc:hist:{driverId}
-- ARGV[1] = driverId, ARGV[2] = lng, ARGV[3] = lat, ARGV[4] = timestamp, ARGV[5] = historyValue, ARGV[6] = ttlSeconds, ARGV[7] = maxHistoryEntries
-- Returns: 1 if updated, 0 if skipped (busy or speed violation)

local geoKey = KEYS[1]
local lastSeenKey = KEYS[2]
local histKey = KEYS[3]
local driverId = ARGV[1]
local lng = tonumber(ARGV[2])
local lat = tonumber(ARGV[3])
local timestamp = tonumber(ARGV[4])
local historyValue = ARGV[5]
local ttlSeconds = tonumber(ARGV[6])
local maxHistoryEntries = tonumber(ARGV[7])

-- Check if driver is busy (busy key is KEYS[4] if passed)
local busyKey = KEYS[4]
if busyKey then
    local isBusy = redis.call('HGET', busyKey, driverId)
    if isBusy then
        return 0  -- Driver is busy, caller will handle separately
    end
end

-- Get last position and time from history for speed check
local lastHist = redis.call('LINDEX', histKey, 0)
local lastSeenScore = redis.call('ZSCORE', lastSeenKey, driverId)

if lastHist and lastSeenScore then
    local parts = {}
    for part in string.gmatch(lastHist, "([^,]+)") do
        table.insert(parts, part)
    end
    if #parts >= 2 then
        local lastLat = tonumber(parts[1])
        local lastLng = tonumber(parts[2])
        local lastTime = tonumber(lastSeenScore)
        local timeDiff = timestamp - lastTime
        if timeDiff > 0 then
            -- Haversine distance in meters
            local R = 6371000
            local dLat = math.rad(lat - lastLat)
            local dLng = math.rad(lng - lastLng)
            local a = math.sin(dLat/2) * math.sin(dLat/2)
                + math.cos(math.rad(lastLat)) * math.cos(math.rad(lat))
                * math.sin(dLng/2) * math.sin(dLng/2)
            local c = 2 * math.atan2(math.sqrt(a), math.sqrt(1-a))
            local distanceM = R * c
            local speedMs = distanceM / (timeDiff / 1000.0)
            if speedMs > 55.56 then  -- 200 km/h
                return -1  -- Speed violation, caller will update lastseen/history only
            end
        end
    end
end

-- Atomic GEOADD + ZADD + LPUSH + LTRIM + EXPIRE
redis.call('GEOADD', geoKey, lng, lat, driverId)
redis.call('ZADD', lastSeenKey, timestamp, driverId)
redis.call('EXPIRE', lastSeenKey, ttlSeconds)
redis.call('LPUSH', histKey, historyValue)
redis.call('LTRIM', histKey, 0, maxHistoryEntries - 1)
redis.call('EXPIRE', histKey, ttlSeconds)
-- Set TTL on GEO key (sorted set) - use EXPIRE on the key
redis.call('EXPIRE', geoKey, ttlSeconds * 2)  -- GEO key TTL longer

return 1