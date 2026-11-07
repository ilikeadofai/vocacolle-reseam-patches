package io.github.ilikeadofai.vocacolle.core.translate

import app.reseam.runtime.Export

// The Translate button's click handler. R8 renamed Function0 in VocaColle, so the patch makes this
// class implement the button's own click type and writes its invoke().
@Export
class TranslateDescriptionClick
