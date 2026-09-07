-- Duas janelas; um único runtime; nenhuma permissão privilegiada.
zxr.window.setTitle("Controle")
local child = zxr.window.create("Janela independente", "WINDOW")
zxr.window.move(.51, .08, child)
zxr.window.resize(.45, .6, child)
zxr.ui.text {windowId=child, text="Mesmo app, outra janela.", x=.07, y=.18}
local button = zxr.ui.button {text="Trazer janela à frente", x=.08, y=.3, width=.8, height=.3}
zxr.ui.on(button, "click", function() zxr.window.focus(child) end)
