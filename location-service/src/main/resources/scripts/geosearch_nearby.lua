-- GEOSEARCH nearby drivers with distance
-- KEYS[1] = loc:geo
-- ARGV[1] = lng, ARGV[2] = lat, ARGV[3] = radiusKm, ARGV[4] = limit
-- Returns array of [driverId, distanceM] pairs

local geoKey = KEYS[1]
local lng = tonumber(ARGV[1])
local lat = tonumber(ARGV[2])
local radiusKm = tonumber(ARGV[3])
local limit = tonumber(ARGV[4])

local results = redis.call('GEOSEARCH', geoKey,
    'FROMLONLAT', lng, lat,
    'BYRADIUS', radiusKm, 'km',
    'WITHDIST', 'ASC', 'COUNT', limit)

local output = {}
for i, item in ipairs(results) do
    -- item is {member, distance}
    local driverId = item[1]
    local distanceKm = item[2]
    local distanceM = distanceKm * 1000
    table.insert(output, driverId)
    table.insert(output, distanceM)
end

return output