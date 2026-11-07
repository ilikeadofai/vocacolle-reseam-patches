package io.github.ilikeadofai.vocacolle.core

import app.reseam.runtime.Export

// The click handler of the Reseam row. R8 renamed Function0 in VocaColle, so the patch makes this
// class implement the row's own click type and writes its invoke().
@Export
class OpenReseamSettings
