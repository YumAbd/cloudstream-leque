# CloudXtream V5.0.8: Smart Play and profile name

- **Smart Play (phase 1):** CloudStream already moves to the next source when the player reports
  an error. V5.0.8 also moves on when a source is still not playing 20 seconds after it was
  selected (stuck loading), with a short "trying the next one" notice. Torrents, paused playback
  and the last available source are left alone.
- **Profile name:** the default profile is named after the license user from the admin panel
  (`display_name` from the Worker on activation and every online check). Renaming the user in the
  panel or with Telegram `/rename` updates it on the next check. A profile the user renamed on the
  phone is never overwritten.
