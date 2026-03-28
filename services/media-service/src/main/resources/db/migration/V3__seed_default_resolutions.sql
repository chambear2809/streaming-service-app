insert into resolutions (id, name, description, height, width, bitrate)
select seeded.id, seeded.name, seeded.description, seeded.height, seeded.width, seeded.bitrate
from (
    values
        ('11111111-1111-1111-1111-111111111111'::uuid, '1080p', 'Default full HD delivery ladder', 1080, 1920, 6000000),
        ('22222222-2222-2222-2222-222222222222'::uuid, '720p', 'Default HD delivery ladder', 720, 1280, 3500000),
        ('33333333-3333-3333-3333-333333333333'::uuid, '480p', 'Default SD delivery ladder', 480, 854, 1800000)
) as seeded(id, name, description, height, width, bitrate)
where not exists (select 1 from resolutions);
