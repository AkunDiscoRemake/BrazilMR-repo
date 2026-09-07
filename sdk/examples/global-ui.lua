-- WINDOW · unsafe_execution. Não concede root, shell, arquivos ou permissões Android.
local button = zxr.ui.button {text="Reposicionar a interface", x=.08, y=.3, width=.84, height=.3}
zxr.ui.on(button, "click", function()
    zxr.ui.recenterGlobal(.5, .5)
    zxr.ui.setGlobalVisible(true)
end)
