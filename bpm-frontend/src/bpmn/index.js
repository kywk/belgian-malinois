/**
 * Module registration for the custom Flowable properties provider.
 */
import FlowablePropertiesProvider from './FlowablePropertiesProvider.js'

export default {
  __init__: ['flowablePropertiesProvider'],
  flowablePropertiesProvider: ['type', FlowablePropertiesProvider]
}
