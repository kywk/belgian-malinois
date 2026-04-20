import AssigneeProps from './AssigneeProps.js'
import FormProps from './FormProps.js'
import WebhookProps from './WebhookProps.js'

const LOW_PRIORITY = 500

export default class FlowablePropertiesProvider {
  constructor(propertiesPanel) {
    propertiesPanel.registerProvider(LOW_PRIORITY, this)
  }

  getGroups(element) {
    return (groups) => {
      if (is(element, 'bpmn:UserTask')) {
        groups.push(AssigneeProps(element))
        groups.push(FormProps(element))
        groups.push(WebhookProps(element))
      }
      if (is(element, 'bpmn:StartEvent')) {
        groups.push(FormProps(element))
      }
      return groups
    }
  }
}

FlowablePropertiesProvider.$inject = ['propertiesPanel']

function is(element, type) {
  try {
    const bo = element?.businessObject || element
    return bo?.$instanceOf?.(type) ?? false
  } catch { return false }
}
