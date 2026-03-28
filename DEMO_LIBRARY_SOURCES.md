Acme Demo Library Sources
=========================

The Kubernetes demo library stages stable MP4 assets into `media-service-demo` at pod startup.

Titles
------
- Big Buck Bunny
  - Asset path: `big-buck-bunny.mp4`
  - Source: https://storage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4
  - License: CC BY 3.0
- Elephants Dream
  - Asset path: `elephants-dream.mp4`
  - Source: https://storage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4
  - License: CC BY 2.5
- Sintel
  - Asset path: `sintel.mp4`
  - Source: https://storage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4
  - License: CC BY 3.0
- Tears of Steel
  - Asset path: `tears-of-steel.mp4`
  - Source: https://storage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4
  - License: CC BY 3.0

Notes
-----
- The legacy `/api/v1/demo/media/movie.mp4` route is still present for the original Big Buck Bunny MP4 demo source.
- The live catalog now points at the backend library routes under `/api/v1/demo/media/library/...`.
