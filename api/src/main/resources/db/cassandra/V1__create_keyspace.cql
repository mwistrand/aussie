-- Create keyspace for Aussie
-- Adjust replication settings for production environments

CREATE KEYSPACE IF NOT EXISTS aussie
WITH replication = {
    'class': 'SimpleStrategy',
    'replication_factor': 1
};

-- For production with multiple datacenters, use NetworkTopologyStrategy:
-- CREATE KEYSPACE IF NOT EXISTS aussie
-- WITH replication = {
--     'class': 'NetworkTopologyStrategy',
--     'datacenter1': 3,
--     'datacenter2': 3
-- };
