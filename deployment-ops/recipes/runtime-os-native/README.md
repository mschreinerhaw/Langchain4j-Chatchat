# Native / Temporal Runtime OS

This is the reviewed public recipe used by native-platform acceptance. It
describes the source-built Native/Temporal product installer and its 24 services,
not the historical SDK deployment. It contains no customer data or credentials.

The images were built from source on `192.168.195.221`, revision
`5892f5b46db9515e0468df268f167f26ff7466ba`. Public source provenance is in
`docs/evidence/native-product-20261117/runtime-source-build-provenance.json`.
The paired offline application ZIP contains every pinned image and public asset;
load it through the authenticated platform import/verification flow.

To prepare a reviewed recipe from another already built installer, use
`scripts/prepare-native-runtime.py`. To publish, use `scripts/build-package.py`
with an authorized private signing key held separately. Never place private keys,
existing installation state or production configuration into the recipe or bundle.
The trusted public key must explicitly authorize `runtime-os-native/v1` and
`runtime-os`; ordinary package signing does not grant Docker administration.

The product installer owns its dependency order, database initialization, Native
Core/Python verification and dedicated execution daemon. Upgrades require declared
data compatibility and unchanged verified schema/infrastructure identities.
