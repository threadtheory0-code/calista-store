-- Lets a banner target one kind of visitor.
-- 'both' (default) = everyone, 'desktop' = laptops and desktops only,
-- 'mobile' = phones and small tablets only.
-- The Worker also applies this automatically on first request, so running
-- this by hand is optional.
ALTER TABLE banners ADD COLUMN device TEXT NOT NULL DEFAULT 'both';
