package net.nekocurit.i4399_ocr

import net.nekocurit.i4399_ocr.model.Model

object Models {
    val model1 by lazy { Model.parse(Resouces.model1) }
    val model2 by lazy { Model.parse(Resouces.model2) }
}