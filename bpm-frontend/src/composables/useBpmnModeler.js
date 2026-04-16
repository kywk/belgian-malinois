import { ref, shallowRef } from 'vue'
import BpmnModeler from 'bpmn-js/lib/Modeler'
import { BpmnPropertiesPanelModule, BpmnPropertiesProviderModule } from 'bpmn-js-properties-panel'
import flowableModdle from './flowableModdle.js'

const modeler = shallowRef(null)
const selectedElement = ref(null)

const EMPTY_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  targetNamespace="http://bpm.com">
  <process id="Process_1" isExecutable="true">
    <startEvent id="StartEvent_1"/>
  </process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="Process_1">
      <bpmndi:BPMNShape id="_BPMNShape_StartEvent_1" bpmnElement="StartEvent_1">
        <dc:Bounds x="180" y="160" width="36" height="36"/>
      </bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`

export function useBpmnModeler() {

  function createModeler(container, propertiesPanelContainer, additionalModules = []) {
    if (modeler.value) modeler.value.destroy()

    const modules = [
      BpmnPropertiesPanelModule,
      BpmnPropertiesProviderModule,
      ...additionalModules
    ]

    modeler.value = new BpmnModeler({
      container,
      propertiesPanel: { parent: propertiesPanelContainer },
      additionalModules: modules,
      moddleExtensions: { flowable: flowableModdle }
    })

    modeler.value.on('selection.changed', (e) => {
      selectedElement.value = e.newSelection?.[0] || null
    })

    return modeler.value
  }

  async function importXml(xml) {
    if (!modeler.value) return
    await modeler.value.importXML(xml || EMPTY_BPMN)
    modeler.value.get('canvas').zoom('fit-viewport')
  }

  async function exportXml() {
    if (!modeler.value) return ''
    const { xml } = await modeler.value.saveXML({ format: true })
    return xml
  }

  function getElement(id) {
    if (!modeler.value) return null
    return modeler.value.get('elementRegistry').get(id)
  }

  function getSelectedElement() {
    return selectedElement.value
  }

  function getModeling() {
    return modeler.value?.get('modeling')
  }

  function on(event, callback) {
    modeler.value?.on(event, callback)
  }

  function destroy() {
    modeler.value?.destroy()
    modeler.value = null
  }

  function newDiagram() {
    return importXml(EMPTY_BPMN)
  }

  return {
    modeler, selectedElement,
    createModeler, importXml, exportXml, newDiagram,
    getElement, getSelectedElement, getModeling,
    on, destroy, EMPTY_BPMN
  }
}
