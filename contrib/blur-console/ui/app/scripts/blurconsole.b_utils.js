/*

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
/*global blurconsole:false */
blurconsole.browserUtils = (function(){
	'use strict';
	var table, modal, cleanId, booleanImg;

	table = function(def, data) {
		var tableMarkup;

		tableMarkup = '<table class="table table-bordered table-condensed table-hover table-striped"><thead><tr>';

		// Add headers
		$.each(def, function(idx, colDef){
			tableMarkup += '<th>' + colDef.label + '</th>';
		});

		tableMarkup += '</tr></thead><tbody>';

		// Add content
		if (data && data.length > 0) {
			$.each(data, function(ir, row){
				tableMarkup += '<tr>';
				$.each(def, function(ic, col) {
					tableMarkup += '<td>';
					if ($.isFunction(col.key)) {
						tableMarkup += col.key(row);
					} else {
						tableMarkup += row[col.key];
					}
					tableMarkup += '</td>';
				});
				tableMarkup += '</tr>';
			});
		} else {
			tableMarkup += '<tr><td colspan="' + def.length + '">There are no items here</td></tr>';
		}

		tableMarkup += '</tbody></table>';
		return tableMarkup;
	};

	modal = function(id, title, content, buttons, size) {
		var mSize, markup, mButtons = buttons;

		switch(size) {
			case 'large':
				mSize = 'modal-lg';
				break;
			case 'medium':
				mSize = 'modal-md';
				break;
			default:
				mSize = 'modal-sm';
		}

		markup = '<div class="modal fade" id="' + id + '">';
		markup += '<div class="modal-dialog ' + mSize + '">';
		markup += '<div class="modal-content">';
		markup += '<div class="modal-header"><button type="button" class="close" data-dismiss="modal">&times;</button><h4 class="modal-title">' + title + '</h4></div>';
		markup += '<div class="modal-body">' + ($.type(content) === 'string' ? content : $(content).html()) + '</div>';

		if (mButtons) {
			if (!$.isArray(mButtons)) {
				mButtons = [mButtons];
			}

			markup += '<div class="modal-footer">';
			$.each(mButtons, function(i, button) {
				markup += '<button type="button" class="btn ' + button.classes + '" id="' + button.id + '" ';

				if (button.data) {
					$.each(button.data, function(key, dataAttr) {
						markup += 'data-' + key + '="' + dataAttr + '" ';
					});
				}
				
				markup += '>' + button.label + '</button> ';
			});
			markup += '</div>';
		}

		markup += '</div></div></div>';

		return markup;
	};

	cleanId = function(str) {
		return str.replace(/([;&,\.\+\*\~':"\!\^#$%@\[\]\(\)=>\|])/g, '\\$1');
	};

	booleanImg = function(val) {
		if (val && (val === true || val === 'yes' || val === 'true')) {
			return '<div class="label label-success"><i class="glyphicon glyphicon-ok-sign"></i></div>';
		}
		return '<div class="label label-danger"><i class="glyphicon glyphicon-minus-sign"></i></div>';
	};

	return {
		table: table,
		modal : modal,
		cleanId : cleanId,
		booleanImg : booleanImg
	};
}());