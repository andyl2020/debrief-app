// `fake-indexeddb` ships types for this deep path but its package.json exports
// map hides them from the bundler-style resolver. Declare the one export the
// tests use rather than letting `any` in.
declare module 'fake-indexeddb/lib/FDBFactory' {
  const FDBFactory: new () => IDBFactory
  export default FDBFactory
}
