CREATE ROLE vkgreplicate WITH LOGIN PASSWORD 'Azerty' REPLICATION;
CREATE PUBLICATION vkgpublication FOR ALL TABLES;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO vkgreplicate;