Streaming Demo Library Assets
=============================

The Kubernetes demo library is now generated in-cluster by
`k8s/backend-demo/media-service.yaml` during pod startup.

Generated Assets
----------------
- `big-buck-bunny.mp4`
- `elephants-dream.mp4`
- `sintel.mp4`
- `tears-of-steel.mp4`
- `sponsor-break.mp4`
- `sponsor-stall.mp4`

Notes
-----
- The generated assets are synthetic booth-safe clips built with FFmpeg, so
  the default rollout no longer depends on downloading public MP4 files from
  the internet.
- `/api/v1/demo/media/movie.mp4` still points at the primary generated house
  asset mounted at `/opt/demo/demo.mp4`.
- If you want branded or externally sourced media, replace the generation step
  in `k8s/backend-demo/media-service.yaml` or provide your own asset staging
  flow before deploy.
